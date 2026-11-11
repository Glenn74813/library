package ph.edu.zscmst.library.inventory;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.catalog.ItemRepository;
import ph.edu.zscmst.library.catalog.ItemStatus;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.Csv;

/** Scanner-based shelf stocktake with missing/found reports. */
@RestController
@RequestMapping("/api/inventory/sessions")
public class InventoryController {

    public record SessionRequest(@NotBlank @Size(max = 120) String name, @Size(max = 100) String location) {
    }

    public record SessionDto(Long id, String name, String location, String startedBy, Instant startedAt,
                             Instant closedAt, long scanned) {
    }

    public record ScanRequest(@NotBlank @Size(max = 64) String code) {
    }

    public record ScanResult(String code, String result, String barcode, String title, ItemStatus status,
                             String location, String message) {
    }

    public record ReportItem(String barcode, String title, String callNumber, String location, ItemStatus status) {
        static ReportItem of(Item i) {
            return new ReportItem(i.getBarcode(), i.getRecord().getTitle(), i.getRecord().getCallNumber(),
                    i.getLocation(), i.getStatus());
        }
    }

    public record Report(SessionDto session, long expected, long found, List<ReportItem> missing,
                         List<ReportItem> misplaced, List<ReportItem> unexpectedStatus, List<String> unknownCodes) {
    }

    /** Statuses of items that should be physically on the shelf. */
    private static final Set<ItemStatus> ON_SHELF = EnumSet.of(ItemStatus.AVAILABLE, ItemStatus.ON_HOLD);

    private final StocktakeSessionRepository sessions;
    private final StocktakeScanRepository scans;
    private final ItemRepository items;
    private final AuditService audit;

    public InventoryController(StocktakeSessionRepository sessions, StocktakeScanRepository scans,
                               ItemRepository items, AuditService audit) {
        this.sessions = sessions;
        this.scans = scans;
        this.items = items;
        this.audit = audit;
    }

    private SessionDto toDto(StocktakeSession s) {
        return new SessionDto(s.getId(), s.getName(), s.getLocation(), s.getStartedBy(), s.getStartedAt(),
                s.getClosedAt(), scans.countBySessionId(s.getId()));
    }

    private StocktakeSession get(Long id) {
        return sessions.findById(id).orElseThrow(() -> ApiException.notFound("Stocktake session"));
    }

    @GetMapping
    public List<SessionDto> list() {
        return sessions.findAllByOrderByStartedAtDesc().stream().map(this::toDto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public SessionDto create(@Valid @RequestBody SessionRequest req) {
        String location = req.location() == null || req.location().isBlank() ? null : req.location().trim();
        StocktakeSession s = sessions.save(new StocktakeSession(req.name().trim(), location,
                AuditService.currentActor()));
        audit.record("STOCKTAKE_START", s.getName());
        return toDto(s);
    }

    @PostMapping("/{id}/scan")
    @Transactional
    public ScanResult scan(@PathVariable Long id, @Valid @RequestBody ScanRequest req) {
        StocktakeSession s = get(id);
        if (s.getClosedAt() != null) throw ApiException.conflict("Session is closed");
        String code = req.code().trim();
        Item item = items.findByIdentifier(code).orElse(null);
        if (scans.existsBySessionIdAndCode(id, code)
                || (item != null && scans.existsBySessionIdAndItemId(id, item.getId()))) {
            return new ScanResult(code, "DUPLICATE", item == null ? null : item.getBarcode(),
                    item == null ? null : item.getRecord().getTitle(), item == null ? null : item.getStatus(),
                    item == null ? null : item.getLocation(), "Already scanned");
        }
        scans.save(new StocktakeScan(s, code, item));
        if (item == null) {
            return new ScanResult(code, "UNKNOWN", null, null, null, null, "Code is not in the catalog");
        }
        String result = "OK";
        String message = "Found";
        if (!ON_SHELF.contains(item.getStatus())) {
            result = "CHECK";
            message = "Catalog status is " + item.getStatus();
        } else if (s.getLocation() != null && !s.getLocation().equalsIgnoreCase(item.getLocation())) {
            result = "MISPLACED";
            message = "Belongs to " + item.getLocation();
        }
        return new ScanResult(code, result, item.getBarcode(), item.getRecord().getTitle(), item.getStatus(),
                item.getLocation(), message);
    }

    @GetMapping("/{id}/report")
    @Transactional(readOnly = true)
    public Report report(@PathVariable Long id) {
        StocktakeSession s = get(id);
        List<StocktakeScan> scanned = scans.findBySessionIdOrderByScannedAtAsc(id);
        Set<Long> scannedIds = scanned.stream().filter(x -> x.getItem() != null)
                .map(x -> x.getItem().getId()).collect(Collectors.toSet());
        List<Item> expected = s.getLocation() == null ? items.findByStatusIn(ON_SHELF)
                : items.findByLocationIgnoreCaseAndStatusIn(s.getLocation(), ON_SHELF);
        List<ReportItem> missing = expected.stream().filter(i -> !scannedIds.contains(i.getId()))
                .map(ReportItem::of).toList();
        List<ReportItem> misplaced = scanned.stream().map(StocktakeScan::getItem)
                .filter(i -> i != null && s.getLocation() != null && ON_SHELF.contains(i.getStatus())
                        && !s.getLocation().equalsIgnoreCase(i.getLocation()))
                .map(ReportItem::of).toList();
        List<ReportItem> unexpected = scanned.stream().map(StocktakeScan::getItem)
                .filter(i -> i != null && !ON_SHELF.contains(i.getStatus()))
                .map(ReportItem::of).toList();
        List<String> unknown = scanned.stream().filter(x -> x.getItem() == null).map(StocktakeScan::getCode).toList();
        long found = expected.stream().filter(i -> scannedIds.contains(i.getId())).count();
        return new Report(toDto(s), expected.size(), found, missing, misplaced, unexpected, unknown);
    }

    @GetMapping("/{id}/report.csv")
    public ResponseEntity<byte[]> reportCsv(@PathVariable Long id) {
        Report r = report(id);
        List<List<?>> rows = new java.util.ArrayList<>();
        r.missing().forEach(i -> rows.add(row("MISSING", i)));
        r.misplaced().forEach(i -> rows.add(row("MISPLACED", i)));
        r.unexpectedStatus().forEach(i -> rows.add(row("FOUND_" + i.status(), i)));
        r.unknownCodes().forEach(c -> rows.add(java.util.Arrays.asList("UNKNOWN", c, "", "", "", "")));
        return Csv.download("stocktake-" + id + ".csv",
                List.of("Result", "Barcode", "Title", "Call Number", "Location", "Catalog Status"), rows);
    }

    private static List<?> row(String result, ReportItem i) {
        return java.util.Arrays.asList(result, i.barcode(), i.title(), i.callNumber(), i.location(), i.status());
    }

    /**
     * Closes the session: optionally marks unscanned shelf items MISSING, and restores scanned
     * LOST/MISSING items to AVAILABLE (they were found).
     */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public SessionDto close(@PathVariable Long id, @RequestBody(required = false) CloseRequest req) {
        StocktakeSession s = get(id);
        if (s.getClosedAt() != null) throw ApiException.conflict("Session is already closed");
        List<StocktakeScan> scanned = scans.findBySessionIdOrderByScannedAtAsc(id);
        Set<Long> scannedIds = scanned.stream().filter(x -> x.getItem() != null)
                .map(x -> x.getItem().getId()).collect(Collectors.toSet());
        int found = 0;
        for (StocktakeScan x : scanned) {
            Item i = x.getItem();
            if (i != null && (i.getStatus() == ItemStatus.LOST || i.getStatus() == ItemStatus.MISSING)) {
                i.setStatus(ItemStatus.AVAILABLE);
                found++;
            }
        }
        int markedMissing = 0;
        if (req != null && req.markMissing()) {
            List<Item> expected = s.getLocation() == null
                    ? items.findByStatusIn(EnumSet.of(ItemStatus.AVAILABLE))
                    : items.findByLocationIgnoreCaseAndStatusIn(s.getLocation(), EnumSet.of(ItemStatus.AVAILABLE));
            for (Item i : expected) {
                if (!scannedIds.contains(i.getId())) {
                    i.setStatus(ItemStatus.MISSING);
                    markedMissing++;
                }
            }
        }
        s.setClosedAt(Instant.now());
        audit.record("STOCKTAKE_CLOSE", s.getName() + " found=" + found + " markedMissing=" + markedMissing);
        return toDto(s);
    }

    public record CloseRequest(boolean markMissing) {
    }
}

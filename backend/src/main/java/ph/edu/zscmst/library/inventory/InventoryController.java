package ph.edu.zscmst.library.inventory;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.catalog.ItemRepository;
import ph.edu.zscmst.library.catalog.ItemStatus;
import ph.edu.zscmst.library.catalog.SectionService;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.circulation.CirculationService;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.Pdf;

/** Scanner-based shelf stocktake with missing/found reports. */
@RestController
@RequestMapping("/api/inventory/sessions")
public class InventoryController {

    public record SessionRequest(@NotBlank @Size(max = 120) String name, @Size(max = 100) String location,
                                 @Size(max = 20) String aisle) {
    }

    public record SessionDto(Long id, String name, String location, String aisle, String startedBy, Instant startedAt,
                             Instant closedAt, long scanned) {
    }

    /** One copy that should be on the shelves being counted, and whether it has been scanned yet. */
    public record ShelfRow(String aisle, String shelf, String callNumber, String title, String barcode, boolean scanned) {
    }

    /** Per-aisle comparison of the shelf list with what was scanned. */
    public record AisleCount(String aisle, long expected, long found, long missing) {
    }

    public record ScanRequest(@NotBlank @Size(max = 64) String code) {
    }

    public record ScanResult(String code, String result, String barcode, String title, ItemStatus status,
                             String location, String message) {
    }

    public record ReportItem(String barcode, String title, String callNumber, String location, ItemStatus status) {
        static ReportItem of(Item i) {
            return new ReportItem(i.getBarcode(), i.getRecord().getTitle(), i.getRecord().getCallNumber(),
                    i.getShelfLocation(), i.getStatus());
        }
    }

    public record Report(SessionDto session, long expected, long found, List<AisleCount> aisles, List<ReportItem> missing,
                         List<ReportItem> misplaced, List<ReportItem> unexpectedStatus, List<String> unknownCodes) {
    }

    /**
     * Statuses of copies that should be on the open shelves. Copies on hold wait on the hold shelf at the
     * desk, so they are not expected in an aisle (scanned there, they are reported to be moved).
     */
    private static final Set<ItemStatus> ON_SHELF = EnumSet.of(ItemStatus.AVAILABLE);
    private static final String NO_AISLE = "No aisle";

    private final StocktakeSessionRepository sessions;
    private final StocktakeScanRepository scans;
    private final ItemRepository items;
    private final AuditService audit;
    private final CirculationService circulation;
    private final SectionService sections;

    public InventoryController(StocktakeSessionRepository sessions, StocktakeScanRepository scans,
                               ItemRepository items, AuditService audit, CirculationService circulation,
                               SectionService sections) {
        this.sections = sections;
        this.circulation = circulation;
        this.sessions = sessions;
        this.scans = scans;
        this.items = items;
        this.audit = audit;
    }

    private SessionDto toDto(StocktakeSession s) {
        return new SessionDto(s.getId(), s.getName(), s.getLocation(), s.getAisle(), s.getStartedBy(), s.getStartedAt(),
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
        // Blank = the whole collection; otherwise one of the library's sections, in the list's spelling.
        String location = sections.require(req.location());
        String aisle = sections.requireAisle(location, req.aisle());
        StocktakeSession s = sessions.save(new StocktakeSession(req.name().trim(), location, aisle,
                AuditService.currentActor()));
        audit.record("STOCKTAKE_START", s.getName() + scope(s));
        return toDto(s);
    }

    /**
     * The shelf list: every copy that should be on the shelves being counted, in shelf order (aisle, shelf,
     * call number), each marked once scanned. Print it before counting, or follow it on screen.
     */
    @GetMapping("/{id}/shelf-list")
    @Transactional(readOnly = true)
    public List<ShelfRow> shelfList(@PathVariable Long id) {
        StocktakeSession s = get(id);
        Set<Long> scannedIds = scannedIds(id);
        return expected(s).stream().sorted(SHELF_ORDER)
                .map(i -> new ShelfRow(aisleOf(i), i.getShelf(), i.getRecord().getCallNumber(), i.getRecord().getTitle(),
                        i.getBarcode(), scannedIds.contains(i.getId())))
                .toList();
    }

    @GetMapping("/{id}/shelf-list/export")
    public ResponseEntity<byte[]> shelfListExport(@PathVariable Long id, @RequestParam(defaultValue = "pdf") String format) {
        StocktakeSession s = get(id);
        List<List<?>> rows = shelfList(id).stream()
                .map(r -> java.util.Arrays.asList(r.aisle(), r.shelf(), r.callNumber(), r.title(), r.barcode(), r.scanned() ? "Yes" : ""))
                .collect(Collectors.toList());
        return Pdf.export(format, "shelf-list-" + id, "Shelf list: " + s.getName(),
                (s.getLocation() == null ? "Whole collection" : s.getLocation()) + (s.getAisle() == null ? "" : " · Aisle " + s.getAisle())
                        + " · " + rows.size() + " copies expected",
                List.of("Aisle", "Shelf", "Call Number", "Title", "Barcode", "Scanned"), rows);
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
        } else if (!belongsHere(s, item)) {
            result = "MISPLACED";
            message = "Belongs to " + (item.getShelfLocation() == null ? "no section" : item.getShelfLocation());
        }
        return new ScanResult(code, result, item.getBarcode(), item.getRecord().getTitle(), item.getStatus(),
                item.getLocation(), message);
    }

    @GetMapping("/{id}/report")
    @Transactional(readOnly = true)
    public Report report(@PathVariable Long id) {
        StocktakeSession s = get(id);
        List<StocktakeScan> scanned = scans.findBySessionIdOrderByScannedAtAsc(id);
        Set<Long> scannedIds = scannedIds(id);
        List<Item> expected = expected(s).stream().sorted(SHELF_ORDER).toList();
        List<ReportItem> missing = expected.stream().filter(i -> !scannedIds.contains(i.getId()))
                .map(ReportItem::of).toList();
        // Shelf list vs. what was scanned, aisle by aisle.
        Map<String, long[]> byAisle = new LinkedHashMap<>();
        for (Item i : expected) {
            long[] c = byAisle.computeIfAbsent(aisleOf(i), k -> new long[2]);
            c[0]++;
            if (scannedIds.contains(i.getId())) c[1]++;
        }
        List<AisleCount> aisles = byAisle.entrySet().stream()
                .map(e -> new AisleCount(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[0] - e.getValue()[1])).toList();
        List<ReportItem> misplaced = scanned.stream().map(StocktakeScan::getItem)
                .filter(i -> i != null && ON_SHELF.contains(i.getStatus()) && !belongsHere(s, i))
                .map(ReportItem::of).toList();
        List<ReportItem> unexpected = scanned.stream().map(StocktakeScan::getItem)
                .filter(i -> i != null && !ON_SHELF.contains(i.getStatus()))
                .map(ReportItem::of).toList();
        List<String> unknown = scanned.stream().filter(x -> x.getItem() == null).map(StocktakeScan::getCode).toList();
        long found = expected.stream().filter(i -> scannedIds.contains(i.getId())).count();
        return new Report(toDto(s), expected.size(), found, aisles, missing, misplaced, unexpected, unknown);
    }

    @GetMapping({"/{id}/report.csv", "/{id}/report/export"})
    public ResponseEntity<byte[]> reportCsv(@PathVariable Long id, @RequestParam(defaultValue = "csv") String format) {
        Report r = report(id);
        List<List<?>> rows = new java.util.ArrayList<>();
        r.aisles().forEach(a -> rows.add(java.util.Arrays.asList(a.aisle().equals(NO_AISLE) ? NO_AISLE.toUpperCase(Locale.ROOT)
                : "AISLE " + a.aisle(), "", a.found() + " of " + a.expected() + " found, " + a.missing() + " missing", "", "", "")));
        r.missing().forEach(i -> rows.add(row("MISSING", i)));
        r.misplaced().forEach(i -> rows.add(row("MISPLACED", i)));
        r.unexpectedStatus().forEach(i -> rows.add(row("FOUND_" + i.status(), i)));
        r.unknownCodes().forEach(c -> rows.add(java.util.Arrays.asList("UNKNOWN", c, "", "", "", "")));
        return Pdf.export(format, "stocktake-" + id, "Stocktake Report: " + r.session().name(), r.found() + " of " + r.expected() + " found",
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
                // Found: it goes to the next waiting hold, if any, rather than straight to the shelf.
                circulation.routeAvailableCopy(i);
                found++;
            }
        }
        int markedMissing = 0;
        if (req != null && req.markMissing()) {
            for (Item i : expected(s)) {
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

    // ------------------------------------------------------------------ helpers

    /** Copies that should be on the shelves this session counts: its section and aisle, or everything. */
    private List<Item> expected(StocktakeSession s) {
        List<Item> list = s.getLocation() == null ? items.findByStatusIn(ON_SHELF)
                : items.findByLocationIgnoreCaseAndStatusIn(s.getLocation(), ON_SHELF);
        if (s.getAisle() == null) return list;
        return list.stream().filter(i -> s.getAisle().equalsIgnoreCase(i.getAisle() == null ? "" : i.getAisle().trim())).toList();
    }

    /** Whether a scanned copy is recorded in the section (and aisle) being counted. */
    private static boolean belongsHere(StocktakeSession s, Item i) {
        if (s.getLocation() == null) return true;
        if (!s.getLocation().equalsIgnoreCase(i.getLocation())) return false;
        return s.getAisle() == null || s.getAisle().equalsIgnoreCase(i.getAisle() == null ? "" : i.getAisle().trim());
    }

    private Set<Long> scannedIds(Long sessionId) {
        return scans.findBySessionIdOrderByScannedAtAsc(sessionId).stream().filter(x -> x.getItem() != null)
                .map(x -> x.getItem().getId()).collect(Collectors.toSet());
    }

    private static String scope(StocktakeSession s) {
        return s.getLocation() == null ? " (whole collection)" : " (" + s.getLocation() + (s.getAisle() == null ? "" : ", aisle " + s.getAisle()) + ")";
    }

    private static String aisleOf(Item i) {
        return i.getAisle() == null || i.getAisle().isBlank() ? NO_AISLE : i.getAisle().trim();
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** Sorts "2" before "10" and "3A" after "3"; "No aisle" last. */
    private static final Comparator<String> NATURAL = Comparator.<String, Boolean>comparing(NO_AISLE::equals)
            .thenComparing(InventoryController::naturalKey);

    private static final Comparator<Item> SHELF_ORDER = Comparator.comparing(InventoryController::aisleOf, NATURAL)
            .thenComparing(i -> i.getShelf() == null ? "" : naturalKey(i.getShelf()))
            .thenComparing(i -> i.getRecord().getCallNumber() == null ? "" : i.getRecord().getCallNumber())
            .thenComparing(Item::getBarcode);

    /**
     * Each run of digits becomes its length (3 digits) followed by the digits without leading zeros, so numbers of
     * any length compare by value as plain text: "2" -> "0012", "10" -> "00210", "007" -> "0017".
     */
    static String naturalKey(String s) {
        Matcher m = DIGITS.matcher(s.toLowerCase(Locale.ROOT));
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceFirst("^0+(?=\\d)", "");
            m.appendReplacement(out, String.format("%03d", digits.length()) + digits);
        }
        m.appendTail(out);
        return out.toString();
    }
}

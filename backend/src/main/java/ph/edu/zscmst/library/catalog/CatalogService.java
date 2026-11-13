package ph.edu.zscmst.library.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.catalog.CatalogDtos.Availability;
import ph.edu.zscmst.library.catalog.CatalogDtos.ItemDto;
import ph.edu.zscmst.library.catalog.CatalogDtos.ItemRequest;
import ph.edu.zscmst.library.catalog.CatalogDtos.RecordDetailDto;
import ph.edu.zscmst.library.catalog.CatalogDtos.RecordDto;
import ph.edu.zscmst.library.catalog.CatalogDtos.RecordRequest;
import ph.edu.zscmst.library.circulation.CirculationService;
import ph.edu.zscmst.library.circulation.LoanRepository;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.authority.Authority;
import ph.edu.zscmst.library.authority.AuthorityService;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.digital.DigitalService;
import ph.edu.zscmst.library.digital.DigitalService.DigitalSummary;

@Service
public class CatalogService {

    private static final Set<String> FIELDS = Set.of("any", "title", "author", "subject", "isbn", "callno", "barcode");

    private final BibRecordRepository records;
    private final ItemRepository items;
    private final LoanRepository loans;
    private final AuditService audit;
    private final DigitalService digital;
    private final LoanPolicy policy;
    private final AuthorityService authorities;
    private final CustomFieldRepository customFields;

    /** Lazy: circulation needs the catalog only indirectly, but keep the two from depending on start order. */
    private final CirculationService circulation;
    private final SectionService sections;
    private final JdbcTemplate jdbc;

    public CatalogService(BibRecordRepository records, ItemRepository items, LoanRepository loans,
                          AuditService audit, DigitalService digital, LoanPolicy policy, AuthorityService authorities,
                          CustomFieldRepository customFields, @Lazy CirculationService circulation,
                          SectionService sections, JdbcTemplate jdbc) {
        this.sections = sections;
        this.jdbc = jdbc;
        this.circulation = circulation;
        this.authorities = authorities;
        this.customFields = customFields;
        this.policy = policy;
        this.records = records;
        this.items = items;
        this.loans = loans;
        this.audit = audit;
        this.digital = digital;
    }

    @Transactional(readOnly = true)
    public Page<RecordDto> search(String q, String field, MaterialType type, Pageable pageable) {
        String f = field == null || !FIELDS.contains(field) ? "any" : field;
        // Authority control: a variant form of a name or subject searches its preferred heading.
        if (q != null && !q.isBlank()) {
            if (f.equals("author") || f.equals("any")) {
                String preferred = authorities.preferred(Authority.Type.AUTHOR, q);
                if (!preferred.equals(q)) { q = preferred; f = "author"; }
            }
            if (f.equals("subject") || f.equals("any")) {
                String preferred = authorities.preferred(Authority.Type.SUBJECT, q);
                if (!preferred.equals(q)) { q = preferred; f = "subject"; }
            }
        }
        Page<BibRecord> page = records.search(q == null ? "" : q.trim(), f, type == null ? "" : type.name(), pageable);
        Map<Long, Availability> availability = availability(page.map(BibRecord::getId).getContent());
        Map<Long, DigitalSummary> ebooks = digital.summaries(page.getContent());
        return page.map(r -> RecordDto.of(r, availability.getOrDefault(r.getId(), Availability.NONE), ebooks.get(r.getId())));
    }

    @Transactional(readOnly = true)
    public List<RecordDto> newArrivals() {
        List<BibRecord> list = records.findTop12ByOrderByCreatedAtDesc();
        Map<Long, Availability> availability = availability(list.stream().map(BibRecord::getId).toList());
        Map<Long, DigitalSummary> ebooks = digital.summaries(list);
        return list.stream().map(r -> RecordDto.of(r, availability.getOrDefault(r.getId(), Availability.NONE),
                ebooks.get(r.getId()))).toList();
    }

    public Map<Long, Availability> availability(Collection<Long> recordIds) {
        Map<Long, int[]> counts = new HashMap<>();
        if (recordIds.isEmpty()) return Map.of();
        for (Object[] row : items.countByRecordAndStatus(recordIds)) {
            Long id = (Long) row[0];
            ItemStatus status = (ItemStatus) row[1];
            int n = ((Number) row[2]).intValue();
            int[] c = counts.computeIfAbsent(id, k -> new int[2]);
            if (status != ItemStatus.WITHDRAWN) c[0] += n;
            if (status == ItemStatus.AVAILABLE) c[1] += n;
        }
        Map<Long, Availability> result = new HashMap<>();
        counts.forEach((id, c) -> result.put(id, new Availability(c[0], c[1])));
        return result;
    }

    @Transactional(readOnly = true)
    public BibRecord getRecord(Long id) {
        return records.findById(id).orElseThrow(() -> ApiException.notFound("Record"));
    }

    @Transactional(readOnly = true)
    public RecordDetailDto detail(Long id) {
        BibRecord r = getRecord(id);
        List<Item> list = items.findByRecordIdOrderByBarcodeAsc(id);
        Map<Long, Instant> due = dueDates(list);
        Availability a = availability(List.of(id)).getOrDefault(id, Availability.NONE);
        return new RecordDetailDto(RecordDto.of(r, a, digital.summaries(List.of(r)).get(id)),
                list.stream().map(i -> ItemDto.of(i, due.get(i.getId()), policy.isRoomUse(i))).toList());
    }

    public Map<Long, Instant> dueDates(List<Item> list) {
        List<Long> onLoan = list.stream().filter(i -> i.getStatus() == ItemStatus.ON_LOAN).map(Item::getId).toList();
        Map<Long, Instant> due = new HashMap<>();
        if (!onLoan.isEmpty()) {
            loans.findActiveByItemIds(onLoan).forEach(l -> due.put(l.getItem().getId(), l.getDueAt()));
        }
        return due;
    }

    @Transactional
    public RecordDto createRecord(RecordRequest req) {
        BibRecord r = new BibRecord();
        apply(r, req);
        r = records.save(r);
        audit.record("RECORD_CREATE", r.getId() + " " + r.getTitle());
        return RecordDto.of(r, Availability.NONE);
    }

    @Transactional
    public RecordDto updateRecord(Long id, RecordRequest req) {
        BibRecord r = getRecord(id);
        apply(r, req);
        records.flush();
        audit.record("RECORD_UPDATE", r.getId() + " " + r.getTitle());
        return RecordDto.of(r, availability(List.of(id)).getOrDefault(id, Availability.NONE));
    }

    @Transactional
    public void deleteRecord(Long id) {
        BibRecord r = getRecord(id);
        if (items.existsByRecordIdAndStatus(id, ItemStatus.ON_LOAN)) {
            throw ApiException.conflict("Record has copies on loan");
        }
        digital.removeFile(id);
        records.delete(r);
        records.flush();
        audit.record("RECORD_DELETE", id + " " + r.getTitle());
    }

    private void apply(BibRecord r, RecordRequest req) {
        r.setTitle(req.title().trim());
        r.setSubtitle(trim(req.subtitle()));
        r.setAuthors(trim(req.authors()));
        r.setIsbn(req.isbn() == null || req.isbn().isBlank() ? null : req.isbn().replace("-", "").trim());
        r.setPublisher(trim(req.publisher()));
        r.setPublicationYear(req.publicationYear());
        r.setEdition(trim(req.edition()));
        r.setSubjects(trim(req.subjects()));
        r.setCallNumber(trim(req.callNumber()));
        r.setMaterialType(req.materialType());
        r.setLanguage(trim(req.language()));
        r.setSummary(trim(req.summary()));
        r.setCustomFields(CatalogDtos.customJson(validCustomValues(req.customFields(), r.getCustomFields())));
    }

    @Transactional(readOnly = true)
    public ItemDto lookupItem(String code) {
        Item i = items.findByIdentifier(code.trim()).orElseThrow(() -> ApiException.notFound("Item"));
        return ItemDto.of(i, dueDates(List.of(i)).get(i.getId()), policy.isRoomUse(i));
    }

    @Transactional
    public ItemDto addItem(Long recordId, ItemRequest req) {
        String code = req.barcode() == null || req.barcode().isBlank() ? nextBarcode() : req.barcode().trim();
        Item i = new Item(getRecord(recordId), code);
        applyItem(i, req, true);
        checkCodesFree(i);
        i = items.save(i);
        // A new copy first serves anyone waiting for the title.
        if (i.getStatus() == ItemStatus.AVAILABLE) circulation.routeAvailableCopy(i);
        audit.record("ITEM_CREATE", i.getBarcode() + " -> record " + recordId);
        return ItemDto.of(i, null, policy.isRoomUse(i));
    }

    @Transactional
    public ItemDto updateItem(Long itemId, ItemRequest req) {
        Item i = items.findById(itemId).orElseThrow(() -> ApiException.notFound("Item"));
        ItemStatus before = i.getStatus();
        // A copy keeps its barcode unless a new one is given: its label is already on the book.
        if (req.barcode() != null && !req.barcode().isBlank()) i.setBarcode(req.barcode().trim());
        applyItem(i, req, false);
        checkCodesFree(i);
        // A copy that is back (found, repaired) goes to the next waiting hold before the shelf.
        if (before != ItemStatus.AVAILABLE && i.getStatus() == ItemStatus.AVAILABLE) circulation.routeAvailableCopy(i);
        items.flush();
        audit.record("ITEM_UPDATE", i.getBarcode() + " status=" + i.getStatus());
        return ItemDto.of(i, dueDates(List.of(i)).get(i.getId()), policy.isRoomUse(i));
    }

    @Transactional
    public void deleteItem(Long itemId) {
        Item i = items.findById(itemId).orElseThrow(() -> ApiException.notFound("Item"));
        if (i.getStatus() == ItemStatus.ON_LOAN) {
            throw ApiException.conflict("Item is on loan");
        }
        if (loans.existsByItemId(itemId)) {
            throw ApiException.conflict("Item has circulation history; set its status to WITHDRAWN instead");
        }
        items.delete(i);
        audit.record("ITEM_DELETE", i.getBarcode());
    }

    /**
     * The next library barcode (LRC- and six digits) for a copy added without one. Skips any number someone
     * already typed in by hand, so the assigned label is always unique.
     */
    private String nextBarcode() {
        while (true) {
            Long n = jdbc.queryForObject("select nextval('item_barcode_seq')", Long.class);
            String code = String.format("LRC-%06d", n);
            if (items.idsUsingCode(code).isEmpty()) return code;
        }
    }

    /** A barcode or EPC may not match (in any case) another copy's barcode or EPC: scans must find one copy. */
    private void checkCodesFree(Item i) {
        for (String code : new String[] {i.getBarcode(), i.getRfidEpc()}) {
            if (code != null && items.idsUsingCode(code).stream().anyMatch(id -> !id.equals(i.getId()))) {
                throw ApiException.conflict("Another copy already uses the code " + code + " (as barcode or RFID tag)");
            }
        }
    }

    private void applyItem(Item i, ItemRequest req, boolean isNew) {
        i.setRfidEpc(trim(req.rfidEpc()) == null ? null : req.rfidEpc().trim().toUpperCase());
        // Section, aisle and shelf come from the layout under Administration → Sections & Aisles.
        SectionService.Spot spot = sections.requireSpot(req.location(), req.aisle(), req.shelf());
        i.setLocation(spot.section());
        i.setAisle(spot.aisle());
        i.setShelf(spot.shelf());
        i.setRoomUseOnly(req.roomUseOnly());
        i.setAcquiredDate(req.acquiredDate());
        i.setPrice(req.price());
        i.setNotes(trim(req.notes()));
        if (req.status() != null && req.status() != i.getStatus()) {
            if (!req.status().isManual() || (!isNew && !i.getStatus().isManual())) {
                throw ApiException.badRequest("Loan/hold status is managed by circulation");
            }
            i.setStatus(req.status());
        }
    }

    /**
     * Keeps only defined fields and checks each value against its type. Values of fields that were removed
     * are carried over from {@code storedJson} untouched: the form can't show them, but they reappear if the
     * field is re-created.
     */
    private Map<String, String> validCustomValues(Map<String, String> values, String storedJson) {
        List<CustomField> defined = customFields.findAll();
        Set<String> definedKeys = defined.stream().map(CustomField::getKey).collect(Collectors.toSet());
        Map<String, String> out = new LinkedHashMap<>();
        CatalogDtos.customValues(storedJson).forEach((k, v) -> { if (!definedKeys.contains(k)) out.put(k, v); });
        if (values == null || values.isEmpty()) return out;
        for (CustomField f : defined) {
            String v = trim(values.get(f.getKey()));
            if (v == null) continue;
            switch (f.getType()) {
                case NUMBER -> {
                    try { new BigDecimal(v); } catch (NumberFormatException e) {
                        throw ApiException.badRequest(f.getLabel() + " must be a number");
                    }
                }
                case DATE -> {
                    try { LocalDate.parse(v); } catch (DateTimeParseException e) {
                        throw ApiException.badRequest(f.getLabel() + " must be a date");
                    }
                }
                case SELECT -> {
                    if (!f.optionList().contains(v)) throw ApiException.badRequest(f.getLabel() + ": choose one of the listed options");
                }
                default -> { }
            }
            out.put(f.getKey(), v);
        }
        return out;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

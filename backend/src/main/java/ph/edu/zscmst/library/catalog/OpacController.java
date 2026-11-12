package ph.edu.zscmst.library.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.catalog.CatalogDtos.Availability;
import ph.edu.zscmst.library.catalog.CatalogDtos.OpacDetailDto;
import ph.edu.zscmst.library.catalog.CatalogDtos.OpacItemDto;
import ph.edu.zscmst.library.catalog.CatalogDtos.RecordDto;
import ph.edu.zscmst.library.common.PageResponse;
import ph.edu.zscmst.library.digital.DigitalService;

/**
 * Public Online Public Access Catalog. Reads the live LMS catalog (not a copy), so availability is
 * real-time. No authentication required; used by OPAC kiosks, the patron portal and the website.
 */
@RestController
@RequestMapping("/api/opac")
public class OpacController {

    private final CatalogService service;
    private final ItemRepository items;
    private final DigitalService digital;
    private final LoanPolicy policy;
    private final CustomFieldRepository customFields;

    public OpacController(CatalogService service, ItemRepository items, DigitalService digital, LoanPolicy policy,
                          CustomFieldRepository customFields) {
        this.customFields = customFields;
        this.policy = policy;
        this.service = service;
        this.items = items;
        this.digital = digital;
    }

    @GetMapping("/search")
    public PageResponse<RecordDto> search(@RequestParam(defaultValue = "") String q,
                                          @RequestParam(defaultValue = "any") String field,
                                          @RequestParam(required = false) MaterialType type,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "12") int size) {
        return PageResponse.of(service.search(q, field, type, PageRequest.of(page, Math.min(size, 50))), RecordDto::withoutCustomFields);
    }

    @GetMapping("/new-arrivals")
    public List<RecordDto> newArrivals() {
        return service.newArrivals().stream().map(RecordDto::withoutCustomFields).toList();
    }

    @GetMapping("/records/{id}")
    @Transactional(readOnly = true)
    public OpacDetailDto record(@PathVariable Long id) {
        BibRecord r = service.getRecord(id);
        List<Item> list = items.findByRecordIdOrderByBarcodeAsc(id).stream()
                .filter(i -> i.getStatus() != ItemStatus.WITHDRAWN)
                .toList();
        Map<Long, Instant> due = service.dueDates(list);
        Availability a = service.availability(List.of(id)).getOrDefault(id, Availability.NONE);
        var values = CatalogDtos.customValues(r.getCustomFields());
        List<CatalogDtos.LabeledValue> extra = customFields.findAllByOrderBySortOrderAscLabelAsc().stream()
                .filter(f -> f.isShowInOpac() && values.containsKey(f.getKey()))
                .map(f -> new CatalogDtos.LabeledValue(f.getLabel(), values.get(f.getKey()))).toList();
        return new OpacDetailDto(RecordDto.of(r, a, digital.summaries(List.of(r)).get(id)).withoutCustomFields(), list.stream()
                .map(i -> new OpacItemDto(i.getBarcode(), i.getLocation(), i.getAisle(), i.getShelf(), i.getShelfLocation(),
                        r.getCallNumber(), i.getStatus(), due.get(i.getId()), policy.isRoomUse(i)))
                .toList(), extra);
    }
}

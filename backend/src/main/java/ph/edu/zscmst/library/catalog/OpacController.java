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

    public OpacController(CatalogService service, ItemRepository items, DigitalService digital) {
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
        return PageResponse.of(service.search(q, field, type, PageRequest.of(page, Math.min(size, 50))), r -> r);
    }

    @GetMapping("/new-arrivals")
    public List<RecordDto> newArrivals() {
        return service.newArrivals();
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
        return new OpacDetailDto(RecordDto.of(r, a, digital.summaries(List.of(r)).get(id)), list.stream()
                .map(i -> new OpacItemDto(i.getBarcode(), i.getLocation(), r.getCallNumber(), i.getStatus(),
                        due.get(i.getId())))
                .toList());
    }
}

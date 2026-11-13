package ph.edu.zscmst.library.catalog;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.catalog.CatalogDtos.IsbnLookupResult;
import ph.edu.zscmst.library.catalog.CatalogDtos.ItemDto;
import ph.edu.zscmst.library.catalog.CatalogDtos.ItemRequest;
import ph.edu.zscmst.library.catalog.CatalogDtos.RecordDetailDto;
import ph.edu.zscmst.library.catalog.CatalogDtos.RecordDto;
import ph.edu.zscmst.library.catalog.CatalogDtos.RecordRequest;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.PageResponse;

/** Staff cataloging API. */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService service;
    private final IsbnLookupService isbnLookup;

    public CatalogController(CatalogService service, IsbnLookupService isbnLookup) {
        this.service = service;
        this.isbnLookup = isbnLookup;
    }

    @GetMapping("/records")
    public PageResponse<RecordDto> search(@RequestParam(defaultValue = "") String q,
                                          @RequestParam(defaultValue = "any") String field,
                                          @RequestParam(required = false) MaterialType type,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        var result = service.search(q, field, type, PageRequest.of(page, Math.min(size, 100)));
        return PageResponse.of(result, r -> r);
    }

    @GetMapping("/records/{id}")
    public RecordDetailDto get(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping("/records")
    @ResponseStatus(HttpStatus.CREATED)
    public RecordDto create(@Valid @RequestBody RecordRequest req) {
        return service.createRecord(req);
    }

    @PutMapping("/records/{id}")
    public RecordDto update(@PathVariable Long id, @Valid @RequestBody RecordRequest req) {
        return service.updateRecord(id, req);
    }

    /** Only a librarian deletes catalog records (with all their copies). */
    @DeleteMapping("/records/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('LIBRARIAN')")
    public void delete(@PathVariable Long id) {
        service.deleteRecord(id);
    }

    @PostMapping("/records/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemDto addItem(@PathVariable Long id, @Valid @RequestBody ItemRequest req) {
        return service.addItem(id, req);
    }

    @PutMapping("/items/{itemId}")
    public ItemDto updateItem(@PathVariable Long itemId, @Valid @RequestBody ItemRequest req) {
        return service.updateItem(itemId, req);
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public void deleteItem(@PathVariable Long itemId) {
        service.deleteItem(itemId);
    }

    /** Resolve a scanned item barcode or RFID EPC. */
    @GetMapping("/items/lookup")
    public ItemDto lookupItem(@RequestParam String code) {
        return service.lookupItem(code);
    }

    /**
     * Catalog records that already have this ISBN (its 10- and 13-digit forms count as the same), so the
     * record form can warn before a second record is made for one edition.
     */
    @GetMapping("/records/same-isbn")
    public List<CatalogService.SameIsbn> sameIsbn(@RequestParam String isbn, @RequestParam(required = false) Long exclude) {
        return service.sameIsbn(isbn, exclude);
    }

    @GetMapping("/isbn/{isbn}")
    public IsbnLookupResult isbn(@PathVariable String isbn) {
        return isbnLookup.lookup(isbn).orElseThrow(() -> ApiException.notFound("ISBN"));
    }
}

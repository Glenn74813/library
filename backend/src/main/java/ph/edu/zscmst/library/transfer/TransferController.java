package ph.edu.zscmst.library.transfer;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.Csv;
import ph.edu.zscmst.library.transfer.CatalogTransferService.ImportReport;

/** Catalog import (migration) and MARC export. */
@RestController
public class TransferController {

    private final CatalogTransferService service;
    private final CopyCatalogService copycat;
    private final AuditService audit;

    public TransferController(CatalogTransferService service, CopyCatalogService copycat, AuditService audit) {
        this.service = service;
        this.copycat = copycat;
        this.audit = audit;
    }

    /**
     * Copy cataloging by ISBN or title/author: {@code source=loc} (default) searches the Library of Congress,
     * {@code source=nlp} the National Library of the Philippines.
     */
    @GetMapping("/api/catalog/copycat")
    public CopyCatalogService.SearchResult copycat(@RequestParam(defaultValue = "loc") String source,
                                                   @RequestParam(required = false) String isbn,
                                                   @RequestParam(required = false) String title,
                                                   @RequestParam(required = false) String author) {
        return copycat.search(source, isbn, title, author);
    }

    /** {@code commit=false} only validates and previews; {@code commit=true} saves. */
    @PostMapping(path = "/api/catalog/import", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public ImportReport importCatalog(@RequestPart("file") MultipartFile file,
                                      @RequestParam(defaultValue = "false") boolean commit) throws IOException {
        return service.importFile(file.getOriginalFilename(), file.getBytes(), commit);
    }

    @GetMapping("/api/catalog/import-template")
    public ResponseEntity<byte[]> template() {
        return Csv.download("catalog-import-template.csv", CatalogTransferService.CSV_COLUMNS, List.of(
                List.of("The Notebook", "", "Sparks, Nicholas", "9780446605236", "Warner Books", "1996", "", "Romance fiction",
                        "FIC SPA 1996", "BOOK", "English", "", "LRC-000123", "Fiction Section", "3", "4"),
                List.of("The Notebook", "", "Sparks, Nicholas", "9780446605236", "Warner Books", "1996", "", "Romance fiction",
                        "FIC SPA 1996", "BOOK", "English", "", "LRC-000124", "Fiction Section", "3", "4")));
    }

    @GetMapping("/api/catalog/export/marc")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public ResponseEntity<byte[]> exportMarc(@RequestParam(defaultValue = "mrc") String format) {
        boolean xml = "xml".equalsIgnoreCase(format);
        byte[] data = service.exportMarc(xml);
        audit.record("CATALOG_EXPORT", "MARC " + (xml ? "XML" : "21") + " (" + data.length + " bytes)");
        String name = "zscmst-catalog-" + LocalDate.now() + (xml ? ".xml" : ".mrc");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(xml ? MediaType.APPLICATION_XML : MediaType.parseMediaType("application/marc"))
                .body(data);
    }
}

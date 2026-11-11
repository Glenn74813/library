package ph.edu.zscmst.library.patron;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ph.edu.zscmst.library.common.Csv;
import ph.edu.zscmst.library.common.PageResponse;
import ph.edu.zscmst.library.patron.PatronDtos.ImportResult;
import ph.edu.zscmst.library.patron.PatronDtos.PatronDto;
import ph.edu.zscmst.library.patron.PatronDtos.PatronRequest;
import ph.edu.zscmst.library.patron.PatronDtos.PinRequest;

@RestController
@RequestMapping("/api/patrons")
public class PatronController {

    private final PatronService service;

    public PatronController(PatronService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<PatronDto> search(@RequestParam(defaultValue = "") String q,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("lastName", "firstName"));
        return PageResponse.of(service.search(q, pageable), PatronDto::of);
    }

    @GetMapping("/{id}")
    public PatronDto get(@PathVariable Long id) {
        return PatronDto.of(service.get(id));
    }

    /** Resolve a scanned card barcode or RFID UID. */
    @GetMapping("/lookup")
    public PatronDto lookup(@RequestParam String code) {
        return PatronDto.of(service.lookup(code));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatronDto create(@Valid @RequestBody PatronRequest req) {
        return PatronDto.of(service.create(req));
    }

    @PutMapping("/{id}")
    public PatronDto update(@PathVariable Long id, @Valid @RequestBody PatronRequest req) {
        return PatronDto.of(service.update(id, req));
    }

    @PostMapping("/{id}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPin(@PathVariable Long id, @Valid @RequestBody PinRequest req) {
        service.setPin(id, req.pin());
    }

    @PostMapping(path = "/import", consumes = "multipart/form-data")
    public ImportResult importCsv(@RequestPart("file") MultipartFile file) throws IOException {
        return service.importCsv(new String(file.getBytes(), StandardCharsets.UTF_8));
    }

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        return Csv.download("patron-import-template.csv", PatronService.IMPORT_COLUMNS, List.of(
                List.of("2024-00001", "Juan", "Dela Cruz", "juan@example.com", "09171234567", "Student", "BSMT",
                        "2026-06-30", "")));
    }
}

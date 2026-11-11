package ph.edu.zscmst.library.digital;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.PageResponse;
import ph.edu.zscmst.library.digital.DigitalService.DigitalInfo;
import ph.edu.zscmst.library.digital.DigitalService.EbookDto;

@RestController
public class DigitalController {

    public record VendorLinkRequest(@Size(max = 1000) String url, @Size(max = 100) String provider) {
    }

    private final DigitalService service;

    public DigitalController(DigitalService service) {
        this.service = service;
    }

    // ------------------------------------------------ staff (cataloging)

    @GetMapping("/api/catalog/records/{id}/digital")
    public DigitalInfo info(@PathVariable Long id) {
        return service.info(id);
    }

    @PostMapping(path = "/api/catalog/records/{id}/digital/file", consumes = "multipart/form-data")
    public DigitalInfo upload(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return service.upload(id, file);
    }

    @DeleteMapping("/api/catalog/records/{id}/digital/file")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id) {
        service.removeFile(id);
    }

    @PutMapping("/api/catalog/records/{id}/digital/vendor")
    public DigitalInfo vendor(@PathVariable Long id, @Valid @RequestBody VendorLinkRequest req) {
        return service.setVendorLink(id, req.url(), req.provider());
    }

    /** Staff preview of the uploaded file. */
    @GetMapping("/api/catalog/records/{id}/digital/file")
    public ResponseEntity<Resource> preview(@PathVariable Long id) {
        return DigitalService.inline(service.open(id, null));
    }

    // ------------------------------------------------ patrons (signed in)

    @GetMapping("/api/me/ebooks")
    public PageResponse<EbookDto> ebooks(@RequestParam(defaultValue = "") String q,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.ebooks(q, PageRequest.of(page, Math.min(size, 50))), e -> e);
    }

    /** School-owned e-books are open to every signed-in patron; commercial ones are never served here. */
    @GetMapping("/api/me/ebooks/{recordId}/content")
    public ResponseEntity<Resource> read(@AuthenticationPrincipal Jwt jwt, @PathVariable Long recordId) {
        Number patronId = jwt.getClaim("patronId");
        if (patronId == null) throw new ApiException(HttpStatus.FORBIDDEN, "Not a patron token");
        return DigitalService.inline(service.open(recordId, patronId.longValue()));
    }
}

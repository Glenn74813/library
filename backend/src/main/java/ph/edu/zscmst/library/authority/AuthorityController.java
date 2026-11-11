package ph.edu.zscmst.library.authority;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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

import ph.edu.zscmst.library.authority.AuthorityService.HeadingRow;

@RestController
@RequestMapping("/api/authorities")
public class AuthorityController {

    public record AuthorityRequest(@NotNull Authority.Type type, @NotBlank @Size(max = 300) String heading,
                                   List<@Size(max = 300) String> variants, @Size(max = 500) String note) {
    }

    public record RenameRequest(@NotNull Authority.Type type, @NotBlank String from, @NotBlank @Size(max = 300) String to) {
    }

    private final AuthorityService service;

    public AuthorityController(AuthorityService service) {
        this.service = service;
    }

    @GetMapping
    public List<HeadingRow> list(@RequestParam Authority.Type type, @RequestParam(defaultValue = "") String q) {
        return service.list(type, q);
    }

    /** Heading suggestions while cataloging. */
    @GetMapping("/suggest")
    public List<String> suggest(@RequestParam Authority.Type type, @RequestParam String q) {
        return service.suggest(type, q);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public HeadingRow create(@Valid @RequestBody AuthorityRequest req) {
        return service.save(req.type(), null, req.heading(), req.variants(), req.note());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public HeadingRow update(@PathVariable Long id, @Valid @RequestBody AuthorityRequest req) {
        return service.save(req.type(), id, req.heading(), req.variants(), req.note());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/rename")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public Map<String, Integer> rename(@Valid @RequestBody RenameRequest req) {
        return Map.of("recordsChanged", service.rename(req.type(), req.from(), req.to()));
    }
}

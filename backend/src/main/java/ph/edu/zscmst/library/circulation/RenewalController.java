package ph.edu.zscmst.library.circulation;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.circulation.RenewalService.RenewalDto;
import ph.edu.zscmst.library.common.PageResponse;

/** Staff decide on patrons' renewal requests. */
@RestController
public class RenewalController {

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {
    }

    private final RenewalService renewals;

    public RenewalController(RenewalService renewals) {
        this.renewals = renewals;
    }

    @GetMapping("/api/renewals/pending")
    public List<RenewalDto> pending() {
        return renewals.pending();
    }

    @GetMapping("/api/renewals/decided")
    public PageResponse<RenewalDto> decided(@RequestParam(defaultValue = "0") int page) {
        return PageResponse.of(renewals.decided(PageRequest.of(page, 25)), r -> r);
    }

    @GetMapping("/api/renewals/patron/{patronId}")
    public List<RenewalDto> ofPatron(@PathVariable Long patronId) {
        return renewals.ofPatron(patronId);
    }

    @PostMapping("/api/renewals/{id}/approve")
    public RenewalDto approve(@PathVariable Long id) {
        return renewals.approve(id);
    }

    @PostMapping("/api/renewals/{id}/reject")
    public RenewalDto reject(@PathVariable Long id, @Valid @RequestBody RejectRequest req) {
        return renewals.reject(id, req.reason());
    }
}

package ph.edu.zscmst.library.report;

import java.time.Instant;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.AuditLog;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.PageResponse;

/** Audit trail viewer (ADMIN only, enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    public record AuditDto(Long id, String actor, String action, String details, Instant createdAt) {
        static AuditDto of(AuditLog a) {
            return new AuditDto(a.getId(), a.getActor(), a.getAction(), a.getDetails(), a.getCreatedAt());
        }
    }

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    public PageResponse<AuditDto> list(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(audit.list(PageRequest.of(page, Math.min(size, 200))), AuditDto::of);
    }
}

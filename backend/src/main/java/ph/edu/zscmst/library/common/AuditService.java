package ph.edu.zscmst.library.common;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records an audit trail of security-relevant and data-changing actions. */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String details) {
        record(currentActor(), action, details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String details) {
        String d = details != null && details.length() > 1000 ? details.substring(0, 1000) : details;
        repository.save(new AuditLog(actor, action, d));
    }

    public Page<AuditLog> list(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            String username = jwt.getToken().getClaimAsString("username");
            return username != null ? username : jwt.getName();
        }
        return auth != null ? auth.getName() : "system";
    }
}

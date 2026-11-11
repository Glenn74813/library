package ph.edu.zscmst.library.circulation;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.catalog.LoanPolicy;
import ph.edu.zscmst.library.catalog.MaterialType;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.SettingsService;

/** Library-wide circulation rules (per-category rules live on patron categories). */
@RestController
public class CirculationSettingsController {

    public record CirculationSettings(@NotNull Set<MaterialType> roomUseTypes,
                                      @NotNull Map<MaterialType, Integer> typeLoanDays,
                                      @Min(1) @Max(365) int bookingHorizonDays,
                                      @Min(0) @Max(30) int atRiskDays,
                                      @NotNull @DecimalMin("0") BigDecimal defaultLostFee,
                                      boolean renewalApproval) {
    }

    private final LoanPolicy policy;
    private final SettingsService settings;
    private final AuditService audit;

    public CirculationSettingsController(LoanPolicy policy, SettingsService settings, AuditService audit) {
        this.policy = policy;
        this.settings = settings;
        this.audit = audit;
    }

    @GetMapping("/api/circulation/settings")
    public CirculationSettings get() {
        return new CirculationSettings(policy.roomUseTypes(), policy.typeLoanDays(), policy.bookingHorizonDays(),
                policy.atRiskDays(), policy.defaultLostFee(), policy.renewalApproval());
    }

    @PutMapping("/api/circulation/settings")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public CirculationSettings update(@Valid @RequestBody CirculationSettings req) {
        policy.setRoomUseTypes(req.roomUseTypes());
        policy.setTypeLoanDays(req.typeLoanDays());
        settings.put("circulation.bookingHorizonDays", String.valueOf(req.bookingHorizonDays()));
        settings.put("circulation.atRiskDays", String.valueOf(req.atRiskDays()));
        settings.put("circulation.defaultLostFee", req.defaultLostFee().toPlainString());
        policy.setRenewalApproval(req.renewalApproval());
        audit.record("CIRCULATION_SETTINGS", "room use only: " + req.roomUseTypes() + "; loan days by type: "
                + policy.typeLoanDays() + "; booking horizon " + req.bookingHorizonDays() + "d; at-risk "
                + req.atRiskDays() + "d; lost fee " + req.defaultLostFee() + "; renewal approval " + req.renewalApproval());
        return get();
    }
}

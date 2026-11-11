package ph.edu.zscmst.library.privacy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.patron.PatronDtos.PatronDto;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class PrivacyController {

    /** Public privacy notice shown on the OPAC, kiosk and patron sign-in. */
    public record Notice(String text, String officer, String contact) {
    }

    public record PrivacySettings(@NotBlank @Size(max = 5000) String notice,
                                  @Size(max = 150) String officer,
                                  @Size(max = 150) String contact,
                                  @Min(0) @Max(50) int retainLoanYears,
                                  @Min(0) @Max(50) int retainAttendanceYears,
                                  @Min(0) @Max(3650) int retainGateDays,
                                  @Min(0) @Max(3650) int retainNoticeDays,
                                  @Min(0) @Max(50) int retainAuditYears,
                                  @Min(0) @Max(50) int anonymizeExpiredYears,
                                  String lastRun) {
    }

    public record AnonymizeRequest(@Size(max = 200) String reason) {
    }

    private final PrivacyService service;
    private final SettingsService settings;
    private final AuditService audit;
    private final JsonMapper json;

    public PrivacyController(PrivacyService service, SettingsService settings, AuditService audit, JsonMapper json) {
        this.service = service;
        this.settings = settings;
        this.audit = audit;
        this.json = json;
    }

    @GetMapping("/api/opac/privacy")
    public Notice notice() {
        return new Notice(settings.get("privacy.notice", ""), settings.get("privacy.officer", ""),
                settings.get("privacy.contact", ""));
    }

    @GetMapping("/api/privacy/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivacySettings getSettings() {
        PrivacyService.Retention r = service.retention();
        return new PrivacySettings(settings.get("privacy.notice", ""), settings.get("privacy.officer", ""),
                settings.get("privacy.contact", ""), r.retainLoanYears(), r.retainAttendanceYears(), r.retainGateDays(),
                r.retainNoticeDays(), r.retainAuditYears(), r.anonymizeExpiredYears(),
                settings.get("privacy.lastRun", null));
    }

    @PutMapping("/api/privacy/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivacySettings updateSettings(@Valid @RequestBody PrivacySettings s) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("notice", s.notice().trim());
        values.put("officer", s.officer() == null ? "" : s.officer().trim());
        values.put("contact", s.contact() == null ? "" : s.contact().trim());
        values.put("retainLoanYears", s.retainLoanYears());
        values.put("retainAttendanceYears", s.retainAttendanceYears());
        values.put("retainGateDays", s.retainGateDays());
        values.put("retainNoticeDays", s.retainNoticeDays());
        values.put("retainAuditYears", s.retainAuditYears());
        values.put("anonymizeExpiredYears", s.anonymizeExpiredYears());
        values.forEach((k, v) -> settings.put("privacy." + k, String.valueOf(v)));
        audit.record("PRIVACY_SETTINGS", "loans=" + s.retainLoanYears() + "y visits=" + s.retainAttendanceYears()
                + "y gate=" + s.retainGateDays() + "d notices=" + s.retainNoticeDays() + "d audit="
                + s.retainAuditYears() + "y expired=" + s.anonymizeExpiredYears() + "y");
        return getSettings();
    }

    /** Preview (dryRun=true, default) or apply the retention limits now. */
    @PostMapping("/api/privacy/retention/run")
    @PreAuthorize("hasRole('ADMIN')")
    public PrivacyService.RetentionResult run(@RequestParam(defaultValue = "true") boolean dryRun,
                                              @AuthenticationPrincipal Jwt jwt) {
        return service.applyRetention(dryRun, jwt.getSubject());
    }

    @GetMapping("/api/patrons/{id}/data-export")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public ResponseEntity<byte[]> exportPatron(@PathVariable Long id) {
        return jsonFile(service.export(id), "patron-" + id + "-data.json");
    }

    @PostMapping("/api/patrons/{id}/anonymize")
    @PreAuthorize("hasRole('ADMIN')")
    public PatronDto anonymize(@PathVariable Long id, @Valid @RequestBody(required = false) AnonymizeRequest req) {
        return PatronDto.of(service.anonymize(id, req == null ? null : req.reason()));
    }

    /** A patron downloads their own data from the patron portal. */
    @GetMapping("/api/me/data-export")
    public ResponseEntity<byte[]> exportMine(@AuthenticationPrincipal Jwt jwt) {
        Number id = jwt.getClaim("patronId");
        if (id == null) throw new ApiException(HttpStatus.FORBIDDEN, "Not a patron token");
        return jsonFile(service.export(id.longValue()), "my-library-data.json");
    }

    private ResponseEntity<byte[]> jsonFile(Object body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(body));
    }
}

package ph.edu.zscmst.library.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.SystemSettings.LibraryProfile;
import ph.edu.zscmst.library.common.SystemSettings.UploadKind;

/** Administration → System settings: library profile, kiosks, sign-in sessions and upload limits. */
@RestController
public class SystemSettingsController {

    public record Profile(@NotBlank @Size(max = 80) String systemName, @NotBlank @Size(max = 120) String name,
                          @Size(max = 160) String institution, @NotBlank @Size(max = 20) String shortName,
                          @Size(max = 250) String address, @Size(max = 60) String phone, @Size(max = 120) String email,
                          @Size(max = 200) String website, @Size(max = 500) String receiptHeader,
                          @Size(max = 500) String receiptFooter) {
    }

    public record Kiosks(@Min(15) @Max(3600) int opacResetSeconds, @Min(0) @Max(3600) int duplicateTapSeconds,
                         boolean selfCheckRequirePin, boolean selfCheckAllowReturns) {
    }

    public record Sessions(@Min(5) @Max(240) int accessTokenMinutes, @Min(1) @Max(90) int refreshTokenDays,
                           @Min(1) @Max(1825) int deviceTokenDays) {
    }

    public record Uploads(@Min(1) @Max(SystemSettings.HARD_UPLOAD_LIMIT_MB) int ebookMaxMb,
                          @Min(1) @Max(SystemSettings.HARD_UPLOAD_LIMIT_MB) int audioMaxMb,
                          @Min(1) @Max(SystemSettings.HARD_UPLOAD_LIMIT_MB) int photoMaxMb) {
    }

    public record Settings(@NotNull @Valid Profile profile, @NotNull @Valid Kiosks kiosks,
                           @NotNull @Valid Sessions sessions, @NotNull @Valid Uploads uploads) {
    }

    /** What every screen (including kiosks and the sign-in pages) needs before anyone signs in. */
    public record PublicConfig(LibraryProfile profile, int opacResetSeconds, Uploads uploads) {
    }

    private final SystemSettings system;
    private final SettingsService settings;
    private final AuditService audit;

    public SystemSettingsController(SystemSettings system, SettingsService settings, AuditService audit) {
        this.system = system;
        this.settings = settings;
        this.audit = audit;
    }

    @GetMapping("/api/opac/profile")
    public PublicConfig publicConfig() {
        return new PublicConfig(system.profile(), system.opacResetSeconds(), uploads());
    }

    private Uploads uploads() {
        return new Uploads(system.uploadLimitMb(UploadKind.EBOOK), system.uploadLimitMb(UploadKind.AUDIO),
                system.uploadLimitMb(UploadKind.PHOTO));
    }

    @GetMapping("/api/settings/system")
    @PreAuthorize("hasRole('ADMIN')")
    public Settings get() {
        LibraryProfile p = system.profile();
        return new Settings(new Profile(p.systemName(), p.name(), p.institution(), p.shortName(), p.address(), p.phone(),
                p.email(), p.website(), p.receiptHeader(), p.receiptFooter()),
                new Kiosks(system.opacResetSeconds(), system.duplicateTapSeconds(),
                        !"false".equals(settings.get("selfcheck.requirePin", "true")),
                        !"false".equals(settings.get("selfcheck.allowReturns", "true"))),
                new Sessions((int) system.accessTokenTtl().toMinutes(), (int) system.refreshTokenTtl().toDays(),
                        (int) system.deviceTokenTtl().toDays()),
                uploads());
    }

    @PutMapping("/api/settings/system")
    @PreAuthorize("hasRole('ADMIN')")
    public Settings update(@Valid @RequestBody Settings s) {
        Profile p = s.profile();
        put("library.systemName", p.systemName());
        put("library.name", p.name());
        put("library.institution", p.institution());
        put("library.shortName", p.shortName());
        put("library.address", p.address());
        put("library.phone", p.phone());
        put("library.email", p.email());
        put("library.website", p.website());
        put("library.receiptHeader", p.receiptHeader());
        put("library.receiptFooter", p.receiptFooter());
        put("kiosk.opacResetSeconds", s.kiosks().opacResetSeconds());
        put("kiosk.duplicateTapSeconds", s.kiosks().duplicateTapSeconds());
        put("selfcheck.requirePin", s.kiosks().selfCheckRequirePin());
        put("selfcheck.allowReturns", s.kiosks().selfCheckAllowReturns());
        put("security.accessTokenMinutes", s.sessions().accessTokenMinutes());
        put("security.refreshTokenDays", s.sessions().refreshTokenDays());
        put("security.deviceTokenDays", s.sessions().deviceTokenDays());
        put("uploads.ebookMaxMb", s.uploads().ebookMaxMb());
        put("uploads.audioMaxMb", s.uploads().audioMaxMb());
        put("uploads.photoMaxMb", s.uploads().photoMaxMb());
        audit.record("SYSTEM_SETTINGS", "profile \"" + p.name() + "\"; OPAC reset " + s.kiosks().opacResetSeconds()
                + "s; double-tap " + s.kiosks().duplicateTapSeconds() + "s; self-check PIN "
                + s.kiosks().selfCheckRequirePin() + ", returns " + s.kiosks().selfCheckAllowReturns() + "; sessions " + s.sessions().accessTokenMinutes()
                + "m/" + s.sessions().refreshTokenDays() + "d/device " + s.sessions().deviceTokenDays() + "d; uploads "
                + s.uploads().ebookMaxMb() + "/" + s.uploads().audioMaxMb() + "/" + s.uploads().photoMaxMb() + " MB");
        return get();
    }

    private void put(String key, Object value) {
        settings.put(key, value == null ? "" : value.toString().trim());
    }
}

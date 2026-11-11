package ph.edu.zscmst.library.common;

import java.time.Duration;

import org.springframework.stereotype.Service;

import ph.edu.zscmst.library.security.JwtProperties;

/**
 * Typed access to the library-wide settings edited in Administration → System settings. Values
 * that were never saved fall back to the defaults (or, for session lengths, the environment).
 */
@Service
public class SystemSettings {

    /** Name, address and contact details shown on screens, receipts, PDFs, e-mails and self-check stations. */
    public record LibraryProfile(String systemName, String name, String institution, String shortName, String address,
                                 String phone, String email, String website, String receiptHeader, String receiptFooter) {
    }

    public enum UploadKind {
        EBOOK("uploads.ebookMaxMb", 100), AUDIO("uploads.audioMaxMb", 10), PHOTO("uploads.photoMaxMb", 5);

        final String key;
        final int defaultMb;

        UploadKind(String key, int defaultMb) {
            this.key = key;
            this.defaultMb = defaultMb;
        }
    }

    /** Uploads can never exceed this, whatever the setting (it matches the server's request size limit). */
    public static final int HARD_UPLOAD_LIMIT_MB = 500;

    private final SettingsService settings;
    private final JwtProperties jwt;

    public SystemSettings(SettingsService settings, JwtProperties jwt) {
        this.settings = settings;
        this.jwt = jwt;
        Pdf.useLibraryName(() -> profile().name());
    }

    public LibraryProfile profile() {
        return new LibraryProfile(text("systemName", "ZSCMST Smart Library"), text("name", "ZSCMST Learning Resource Center"),
                text("institution", "Zamboanga State College of Marine Sciences and Technology"), text("shortName", "ZSCMST"),
                text("address", ""), text("phone", ""), text("email", ""), text("website", ""),
                text("receiptHeader", ""), text("receiptFooter", ""));
    }

    private String text(String key, String fallback) {
        String v = settings.get("library." + key, null);
        return v == null || (v.isBlank() && !fallback.isEmpty()) ? fallback : v;
    }

    public int integer(String key, int fallback, int min, int max) {
        try {
            return Math.clamp(Long.parseLong(settings.get(key, String.valueOf(fallback)).trim()), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Duration accessTokenTtl() {
        return minutes("security.accessTokenMinutes", jwt.accessTokenTtl(), 5, 240);
    }

    public Duration refreshTokenTtl() {
        return days("security.refreshTokenDays", jwt.refreshTokenTtl(), 1, 90);
    }

    public Duration deviceTokenTtl() {
        return days("security.deviceTokenDays", jwt.kioskTokenTtl(), 1, 1825);
    }

    private Duration minutes(String key, Duration fallback, int min, int max) {
        return Duration.ofMinutes(integer(key, (int) fallback.toMinutes(), min, max));
    }

    private Duration days(String key, Duration fallback, int min, int max) {
        return Duration.ofDays(integer(key, (int) fallback.toDays(), min, max));
    }

    public int uploadLimitMb(UploadKind kind) {
        return integer(kind.key, kind.defaultMb, 1, HARD_UPLOAD_LIMIT_MB);
    }

    /** Throws a friendly error when an upload is over its configured limit. */
    public void checkUpload(UploadKind kind, long bytes) {
        int mb = uploadLimitMb(kind);
        if (bytes > mb * 1024L * 1024L) throw ApiException.badRequest("The file is too large (limit " + mb + " MB)");
    }

    public int opacResetSeconds() {
        return integer("kiosk.opacResetSeconds", 90, 15, 3600);
    }

    public int duplicateTapSeconds() {
        return integer("kiosk.duplicateTapSeconds", 15, 0, 3600);
    }
}

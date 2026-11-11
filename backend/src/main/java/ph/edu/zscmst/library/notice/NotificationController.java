package ph.edu.zscmst.library.notice;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.common.SystemSettings;

/** Notification settings: delivery channels, schedule and message templates (admins and librarians). */
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
public class NotificationController {

    /** Secrets are never returned; {@code passwordSet}/{@code apiKeySet} tell whether one is stored. */
    public record NotificationSettings(
            boolean mailEnabled, @Size(max = 200) String mailHost, @Min(1) @Max(65535) int mailPort,
            @Size(max = 200) String mailUsername, @Size(max = 200) String mailPassword, boolean passwordSet,
            @Size(max = 200) String mailFrom, boolean mailStarttls,
            boolean smsEnabled, @Size(max = 500) String smsUrl, @Size(max = 300) String smsApiKey, boolean apiKeySet,
            @Size(max = 30) String smsSender,
            @Min(0) @Max(23) int sendHour, @Min(1) @Max(14) int dueSoonDays,
            @NotNull Map<NoticeType, @NotBlank @Size(max = 600) String> templates) {
    }

    public record TestRequest(@NotBlank String channel, @NotBlank @Size(max = 200) String to) {
    }

    public record NoticeDto(Long id, String patronName, String cardNumber, NoticeType type, String message,
                            Instant createdAt, Instant sentAt, String channels, int attempts, String lastError) {
        static NoticeDto of(Notice n) {
            return new NoticeDto(n.getId(), n.getPatron().getFullName(), n.getPatron().getCardNumber(), n.getType(),
                    n.getMessage(), n.getCreatedAt(), n.getSentAt(), n.getChannels(), n.getAttempts(), n.getLastError());
        }
    }

    private final SettingsService settings;
    private final SettingsNoticeSender sender;
    private final NoticeRepository notices;
    private final AuditService audit;

    private final SystemSettings system;

    public NotificationController(SettingsService settings, SettingsNoticeSender sender, NoticeRepository notices,
                                  AuditService audit, SystemSettings system) {
        this.system = system;
        this.settings = settings;
        this.sender = sender;
        this.notices = notices;
        this.audit = audit;
    }

    @GetMapping("/settings")
    public NotificationSettings get() {
        return new NotificationSettings(settings.getBoolean("mail.enabled"), settings.get("mail.host", ""),
                Integer.parseInt(settings.get("mail.port", "587")), settings.get("mail.username", ""), "",
                !settings.get("mail.password", "").isBlank(), settings.get("mail.from", ""),
                Boolean.parseBoolean(settings.get("mail.starttls", "true")),
                settings.getBoolean("sms.enabled"), settings.get("sms.url", ""), "",
                !settings.get("sms.apiKey", "").isBlank(), settings.get("sms.sender", ""),
                Integer.parseInt(settings.get("notice.sendHour", "7")),
                Integer.parseInt(settings.get("notice.dueSoonDays", "2")),
                Map.of(NoticeType.DUE_SOON, settings.get("notice.template.DUE_SOON", ""),
                        NoticeType.OVERDUE, settings.get("notice.template.OVERDUE", ""),
                        NoticeType.HOLD_READY, settings.get("notice.template.HOLD_READY", "")));
    }

    @PutMapping("/settings")
    @Transactional
    public NotificationSettings update(@Valid @RequestBody NotificationSettings s) {
        settings.put("mail.enabled", String.valueOf(s.mailEnabled()));
        settings.put("mail.host", blank(s.mailHost()));
        settings.put("mail.port", String.valueOf(s.mailPort()));
        settings.put("mail.username", blank(s.mailUsername()));
        if (s.mailPassword() != null && !s.mailPassword().isBlank()) settings.put("mail.password", s.mailPassword());
        if (blank(s.mailUsername()).isEmpty()) settings.put("mail.password", "");
        settings.put("mail.from", blank(s.mailFrom()));
        settings.put("mail.starttls", String.valueOf(s.mailStarttls()));
        settings.put("sms.enabled", String.valueOf(s.smsEnabled()));
        settings.put("sms.url", blank(s.smsUrl()));
        if (s.smsApiKey() != null && !s.smsApiKey().isBlank()) settings.put("sms.apiKey", s.smsApiKey());
        settings.put("sms.sender", blank(s.smsSender()));
        settings.put("notice.sendHour", String.valueOf(s.sendHour()));
        settings.put("notice.dueSoonDays", String.valueOf(s.dueSoonDays()));
        s.templates().forEach((type, text) -> settings.put("notice.template." + type, text.trim()));
        audit.record("NOTIFICATION_SETTINGS", "email=" + s.mailEnabled() + " sms=" + s.smsEnabled()
                + " sendHour=" + s.sendHour() + " dueSoonDays=" + s.dueSoonDays());
        return get();
    }

    /** Sends a test message with the saved settings, so staff can confirm the channel works. */
    @PostMapping("/test")
    public Map<String, String> test(@Valid @RequestBody TestRequest req) {
        String text = "This is a test message from the " + system.profile().systemName()
                + ". If you received it, notifications work.";
        try {
            switch (req.channel()) {
                case "EMAIL" -> sender.sendEmail(req.to().trim(), sender.subjectPrefix() + "test message", text);
                case "SMS" -> sender.sendSms(req.to().trim(), text);
                default -> throw ApiException.badRequest("Unknown channel");
            }
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Sending failed: " + e.getMessage());
        }
        return Map.of("result", "Test " + req.channel().toLowerCase() + " sent to " + req.to().trim());
    }

    @GetMapping("/recent")
    @Transactional(readOnly = true)
    public List<NoticeDto> recent() {
        return notices.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 50)).map(NoticeDto::of).getContent();
    }

    private static String blank(String s) {
        return s == null ? "" : s.trim();
    }
}

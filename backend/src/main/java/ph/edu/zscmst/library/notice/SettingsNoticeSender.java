package ph.edu.zscmst.library.notice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.common.SystemSettings;
import ph.edu.zscmst.library.patron.Patron;

/**
 * Sends notices by e-mail (SMTP) and/or SMS (HTTP gateway), configured on the Notifications admin
 * page. A patron with no reachable contact is recorded as delivered on channel NONE so the notice
 * isn't retried forever.
 */
@Component
public class SettingsNoticeSender implements NoticeSender {

    private static final Logger log = LoggerFactory.getLogger(SettingsNoticeSender.class);

    static final Map<NoticeType, String> SUBJECTS = Map.of(
            NoticeType.DUE_SOON, "Library item due soon",
            NoticeType.OVERDUE, "Library item overdue",
            NoticeType.HOLD_READY, "Your reserved item is ready for pickup",
            NoticeType.RENEWAL_APPROVED, "Your renewal was approved",
            NoticeType.RENEWAL_REJECTED, "Your renewal was not approved");

    private final SettingsService settings;
    private final SystemSettings system;

    public SettingsNoticeSender(SettingsService settings, SystemSettings system) {
        this.settings = settings;
        this.system = system;
    }

    /** e.g. "ZSCMST Library: " */
    public String subjectPrefix() {
        return system.profile().shortName() + " Library: ";
    }

    @Override
    public Delivery send(Notice n) {
        Patron p = n.getPatron();
        List<String> sent = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean tried = false;
        if (settings.getBoolean("mail.enabled") && p.getEmail() != null && !p.getEmail().isBlank()) {
            tried = true;
            try {
                sendEmail(p.getEmail(), subjectPrefix() + SUBJECTS.getOrDefault(n.getType(), "Notice"), n.getMessage());
                sent.add("EMAIL");
            } catch (RuntimeException e) {
                errors.add("email: " + e.getMessage());
            }
        }
        if (settings.getBoolean("sms.enabled") && p.getPhone() != null && !p.getPhone().isBlank()) {
            tried = true;
            try {
                sendSms(p.getPhone(), n.getMessage());
                sent.add("SMS");
            } catch (RuntimeException e) {
                errors.add("sms: " + e.getMessage());
            }
        }
        if (!tried) {
            log.info("[notice:{}] no channel for {}: {}", n.getType(), p.getFullName(), n.getMessage());
            return new Delivery(true, "NONE", null);
        }
        return new Delivery(!sent.isEmpty(), sent.isEmpty() ? null : String.join(",", sent),
                errors.isEmpty() ? null : String.join("; ", errors));
    }

    public void sendEmail(String to, String subject, String text) {
        JavaMailSenderImpl mail = new JavaMailSenderImpl();
        mail.setHost(require("mail.host", "SMTP host"));
        mail.setPort(Integer.parseInt(settings.get("mail.port", "587")));
        String user = settings.get("mail.username", "");
        if (!user.isBlank()) {
            mail.setUsername(user);
            mail.setPassword(settings.get("mail.password", ""));
        }
        Properties props = mail.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(!user.isBlank()));
        props.put("mail.smtp.starttls.enable", settings.get("mail.starttls", "true"));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(require("mail.from", "sender address"));
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(text + "\n\n— " + system.profile().name());
        mail.send(msg);
    }

    /**
     * Generic SMS gateway call: POST {"to", "message", "sender"} as JSON with the API key as a
     * Bearer token. Most Philippine SMS providers accept this shape or need a thin adapter.
     */
    public void sendSms(String to, String message) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10000);
        f.setReadTimeout(15000);
        RestClient.builder().requestFactory(f).build().post()
                .uri(require("sms.url", "SMS gateway URL"))
                .header("Authorization", "Bearer " + settings.get("sms.apiKey", ""))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("to", to, "message", message, "sender", settings.get("sms.sender", "")))
                .retrieve().toBodilessEntity();
    }

    private String require(String key, String what) {
        String v = settings.get(key, "");
        if (v.isBlank()) throw new IllegalStateException(what + " is not configured");
        return v;
    }
}

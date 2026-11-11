package ph.edu.zscmst.library.notice;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.circulation.LoanRepository;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.patron.Patron;

/** Generates due-soon / overdue / hold-ready notices from editable templates and dispatches them. */
@Service
public class NoticeService {

    private static final Logger log = LoggerFactory.getLogger(NoticeService.class);

    private final NoticeRepository notices;
    private final LoanRepository loans;
    private final SettingsService settings;
    private final LibraryClock clock;
    private final NoticeSender sender;

    /** Give up on a notice after this many failed delivery attempts. */
    static final int MAX_ATTEMPTS = 5;

    public NoticeService(NoticeRepository notices, LoanRepository loans, SettingsService settings, LibraryClock clock,
                         NoticeSender sender) {
        this.notices = notices;
        this.loans = loans;
        this.settings = settings;
        this.clock = clock;
        this.sender = sender;
    }

    @Transactional
    public void queue(Patron patron, NoticeType type, String title, Instant date, String reference) {
        if (notices.existsByTypeAndReference(type, reference)) return;
        String template = settings.get("notice.template." + type, "{name}: {title} ({due})");
        String message = render(template, Map.of("name", patron.getFirstName(), "title", title,
                "due", clock.format(date)));
        notices.save(new Notice(patron, type, message, reference));
    }

    static String render(String template, Map<String, String> values) {
        String result = template;
        for (var e : values.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return result;
    }

    /** Hourly check: once a day, at the configured hour, scan for loans that are due soon or overdue. */
    @Scheduled(cron = "0 1 * * * *", zone = "${app.library.timezone}")
    @Transactional
    public void dailyRun() {
        LocalDate today = clock.today();
        int hour = Integer.parseInt(settings.get("notice.sendHour", "7"));
        if (LocalTime.now(clock.zone()).getHour() < hour || today.toString().equals(settings.get("notice.lastRun", ""))) return;
        settings.put("notice.lastRun", today.toString());
        generateDailyNotices();
    }

    @Transactional
    public void generateDailyNotices() {
        LocalDate today = clock.today();
        int dueSoonDays = Integer.parseInt(settings.get("notice.dueSoonDays", "2"));
        Instant now = Instant.now();
        loans.findByReturnedAtIsNullAndDueAtBetween(now, clock.endOfDay(today.plusDays(dueSoonDays)))
                .forEach(l -> queue(l.getPatron(), NoticeType.DUE_SOON, l.getItem().getRecord().getTitle(),
                        l.getDueAt(), "loan:" + l.getId() + ":" + l.getDueAt().getEpochSecond()));
        loans.findByReturnedAtIsNullAndDueAtBefore(now)
                .forEach(l -> queue(l.getPatron(), NoticeType.OVERDUE, l.getItem().getRecord().getTitle(),
                        l.getDueAt(), "loan:" + l.getId() + ":" + l.getDueAt().getEpochSecond()));
        dispatchPending();
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @Transactional
    public void dispatchPending() {
        for (Notice n : notices.findTop200BySentAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(MAX_ATTEMPTS)) {
            NoticeSender.Delivery d;
            try {
                d = sender.send(n);
            } catch (RuntimeException e) {
                d = new NoticeSender.Delivery(false, null, e.getMessage());
            }
            n.setAttempts(n.getAttempts() + 1);
            n.setLastError(d.error());
            if (d.delivered()) {
                n.setSentAt(Instant.now());
                n.setChannels(d.channels());
            } else {
                log.warn("Notice {} not delivered (attempt {}): {}", n.getId(), n.getAttempts(), d.error());
            }
        }
    }
}

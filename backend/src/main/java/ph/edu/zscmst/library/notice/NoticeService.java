package ph.edu.zscmst.library.notice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final int dueSoonDays;

    public NoticeService(NoticeRepository notices, LoanRepository loans, SettingsService settings, LibraryClock clock,
                         NoticeSender sender, @Value("${app.library.due-soon-days}") int dueSoonDays) {
        this.notices = notices;
        this.loans = loans;
        this.settings = settings;
        this.clock = clock;
        this.sender = sender;
        this.dueSoonDays = dueSoonDays;
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

    /** Daily scan for loans that are due soon or overdue. */
    @Scheduled(cron = "${app.library.notice-cron:0 0 7 * * *}", zone = "${app.library.timezone}")
    @Transactional
    public void generateDailyNotices() {
        LocalDate today = clock.today();
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
        for (Notice n : notices.findTop200BySentAtIsNullOrderByCreatedAtAsc()) {
            try {
                if (sender.send(n)) n.setSentAt(Instant.now());
            } catch (RuntimeException e) {
                log.warn("Failed to send notice {}: {}", n.getId(), e.getMessage());
            }
        }
    }
}

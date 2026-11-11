package ph.edu.zscmst.library.attendance;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.common.VoiceSettings;
import ph.edu.zscmst.library.gate.BroadcastService;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronRepository;

/** Library attendance kiosk: time-in/time-out by RFID card, barcode or manual entry. */
@Service
public class AttendanceService {

    public record KioskConfig(String mode, boolean singleTap, String entryMessage, String exitMessage,
                              List<String> standbyMessages, String videoUrl, Broadcast broadcast, VoiceSettings voice) {
    }

    public record Broadcast(String message, Instant at, String presetId, boolean hasAudio) {
    }

    public record TapRequest(String code, EntryMethod method, Instant timestamp) {
    }

    public record TapResult(String action, String patronName, String cardNumber, String course, String category,
                            String photoUrl, String message, Instant time, List<String> warnings) {
    }

    public record LogDto(Long id, Long patronId, String patronName, String cardNumber, String course, String category,
                         Instant timeIn, Instant timeOut, EntryMethod method) {
        static LogDto of(AttendanceLog a) {
            Patron p = a.getPatron();
            return new LogDto(a.getId(), p.getId(), p.getFullName(), p.getCardNumber(),
                    p.getCourse() == null ? null : p.getCourse().getCode(), p.getCategory().getName(),
                    a.getTimeIn(), a.getTimeOut(), a.getMethod());
        }
    }

    public record Count(String label, long count) {
    }

    public record Stats(long totalVisits, long uniquePatrons, long currentlyInside, List<Count> topCourses,
                        List<Count> topCategories, List<Count> daily, List<Count> hourly) {
    }

    /** Ignore repeated taps of the same card within this window (people double-tap). */
    private static final Duration DEBOUNCE = Duration.ofSeconds(15);

    private final AttendanceLogRepository logs;
    private final PatronRepository patrons;
    private final SettingsService settings;
    private final LibraryClock clock;
    private final BroadcastService broadcasts;

    public AttendanceService(AttendanceLogRepository logs, PatronRepository patrons, SettingsService settings,
                             LibraryClock clock, BroadcastService broadcasts) {
        this.broadcasts = broadcasts;
        this.logs = logs;
        this.patrons = patrons;
        this.settings = settings;
        this.clock = clock;
    }

    public KioskConfig config() {
        Map<String, String> k = settings.getByPrefix("kiosk.");
        BroadcastService.CurrentBroadcast c = broadcasts.current();
        Broadcast broadcast = c == null ? null : new Broadcast(c.message(), c.at(), c.presetId(), c.hasAudio());
        return new KioskConfig(k.getOrDefault("mode", "ATTENDANCE"), Boolean.parseBoolean(k.get("singleTap")),
                k.getOrDefault("entryMessage", ""), k.getOrDefault("exitMessage", ""),
                Arrays.stream(k.getOrDefault("standbyMessages", "").split("\\|"))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList(),
                k.getOrDefault("videoUrl", ""), broadcast, VoiceSettings.load(settings));
    }

    @Transactional
    public TapResult tap(TapRequest req) {
        if (req.code() == null || req.code().isBlank()) throw ApiException.badRequest("No ID was read");
        Patron p = patrons.findByIdentifier(req.code().trim())
                .orElseThrow(() -> ApiException.notFound("Library card"));
        Instant now = req.timestamp() != null && req.timestamp().isBefore(Instant.now()) ? req.timestamp() : Instant.now();
        EntryMethod method = req.method() == null ? EntryMethod.MANUAL : req.method();
        KioskConfig cfg = config();
        LocalDate day = clock.toDate(now);
        Optional<AttendanceLog> last = logs.findFirstByPatronIdAndTimeInAfterOrderByTimeInDesc(p.getId(),
                clock.startOfDay(day));

        List<String> warnings = new java.util.ArrayList<>();
        if (p.isBlocked()) warnings.add("Account is blocked - please see the librarian");
        if (p.isExpired(day)) warnings.add("Library card expired on " + p.getExpiryDate());

        if (last.isPresent()) {
            AttendanceLog l = last.get();
            Instant lastAction = l.getTimeOut() != null ? l.getTimeOut() : l.getTimeIn();
            if (Duration.between(lastAction, now).abs().compareTo(DEBOUNCE) < 0) {
                String action = l.getTimeOut() != null ? "OUT" : "IN";
                return result(p, action, "Already recorded", lastAction, warnings);
            }
            if (!cfg.singleTap() && l.getTimeOut() == null) {
                l.setTimeOut(now);
                return result(p, "OUT", cfg.exitMessage(), now, warnings);
            }
        }
        logs.save(new AttendanceLog(p, now, method));
        return result(p, "IN", cfg.entryMessage(), now, warnings);
    }

    private static TapResult result(Patron p, String action, String message, Instant time, List<String> warnings) {
        return new TapResult(action, p.getFullName(), p.getCardNumber(),
                p.getCourse() == null ? null : p.getCourse().getCode(), p.getCategory().getName(), p.getPhotoUrl(),
                message, time, warnings);
    }

    @Transactional(readOnly = true)
    public Page<LogDto> logs(LocalDate from, LocalDate to, Pageable pageable) {
        return logs.findByTimeInBetweenOrderByTimeInDesc(clock.startOfDay(from), clock.startOfDay(to.plusDays(1)),
                pageable).map(LogDto::of);
    }

    @Transactional(readOnly = true)
    public List<LogDto> allLogs(LocalDate from, LocalDate to) {
        return logs.findByTimeInBetweenOrderByTimeInAsc(clock.startOfDay(from), clock.startOfDay(to.plusDays(1)))
                .stream().map(LogDto::of).toList();
    }

    @Transactional(readOnly = true)
    public Stats stats(LocalDate from, LocalDate to) {
        Instant start = clock.startOfDay(from);
        Instant end = clock.startOfDay(to.plusDays(1));
        String tz = clock.zone().getId();
        PageRequest top5 = PageRequest.of(0, 5);
        return new Stats(logs.countByTimeInBetween(start, end), logs.countDistinctPatrons(start, end),
                logs.countByTimeInAfterAndTimeOutIsNull(clock.startOfDay(clock.today())),
                counts(logs.countByCourse(start, end, top5)), counts(logs.countByCategory(start, end, top5)),
                counts(logs.countByDay(start, end, tz)), counts(logs.countByHour(start, end, tz)));
    }

    private static List<Count> counts(List<Object[]> rows) {
        return rows.stream().map(r -> new Count(String.valueOf(r[0]), ((Number) r[1]).longValue())).toList();
    }
}

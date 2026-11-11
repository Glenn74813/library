package ph.edu.zscmst.library.gate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;

import ph.edu.zscmst.library.gate.BroadcastService.Preset;


import ph.edu.zscmst.library.calendar.LibraryCalendar;
import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.catalog.ItemRepository;
import ph.edu.zscmst.library.catalog.ItemStatus;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.Pdf;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.common.PageResponse;
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.common.VoiceSettings;

/**
 * RFID security gate integration. The gate controller (or its middleware) calls the {@code /device}
 * endpoints with a GATE device token; staff use the rest for monitoring and configuration.
 */
@RestController
public class GateController {

    public record CheckRequest(@NotBlank @Size(max = 40) String gateId, @NotNull @Size(max = 200) List<String> epcs) {
    }

    public record TagResult(String epc, String barcode, String title, ItemStatus status, boolean authorized) {
    }

    public record CheckResponse(boolean alarm, boolean emergencyMode, List<TagResult> tags) {
    }

    public record DeviceEventRequest(@NotBlank @Size(max = 40) String gateId, @NotNull GateEventType type,
                                     Integer visitorCount, @Size(max = 500) String message) {
    }

    /**
     * {@code schedule}: ALWAYS, OPEN_HOURS (library opening hours, from 15 min before opening to 30 min
     * after closing) or CUSTOM ({@code armFrom}-{@code armTo} every day). {@code armedNow} is read-only.
     */
    public record GateConfig(boolean emergencyMode, boolean alarmEnabled, String schedule, LocalTime armFrom,
                             LocalTime armTo, boolean armedNow) {
    }

    public record BroadcastRequest(@NotBlank @Size(max = 500) String message, String presetId) {
    }


    public record EventDto(Long id, String gateId, GateEventType type, String epc, String barcode, String title,
                           Integer visitorCount, String message, Instant occurredAt) {
        static EventDto of(GateEvent e) {
            Item i = e.getItem();
            return new EventDto(e.getId(), e.getGateId(), e.getEventType(), e.getEpc(),
                    i == null ? null : i.getBarcode(), i == null ? null : i.getRecord().getTitle(),
                    e.getVisitorCount(), e.getMessage(), e.getOccurredAt());
        }
    }

    public record GateStatus(String gateId, Instant lastSeen, boolean online) {
    }

    public record Summary(long alarmsToday, long visitorsToday, long tamperToday, long powerLossToday,
                          boolean emergencyMode, boolean alarmEnabled, List<GateStatus> gates, GateConfig config) {
    }

    private final GateEventRepository events;
    private final ItemRepository items;
    private final SettingsService settings;
    private final LibraryClock clock;
    private final AuditService audit;
    private final BroadcastService broadcasts;
    private final LibraryCalendar calendar;

    public GateController(GateEventRepository events, ItemRepository items, SettingsService settings,
                          LibraryClock clock, AuditService audit, BroadcastService broadcasts, LibraryCalendar calendar) {
        this.calendar = calendar;
        this.events = events;
        this.items = items;
        this.settings = settings;
        this.clock = clock;
        this.audit = audit;
        this.broadcasts = broadcasts;
    }

    private GateConfig config() {
        String schedule = settings.get("gate.schedule", "ALWAYS");
        LocalTime from = parseTime(settings.get("gate.armFrom", "07:00"));
        LocalTime to = parseTime(settings.get("gate.armTo", "19:00"));
        return new GateConfig(settings.getBoolean("gate.emergencyMode"), settings.getBoolean("gate.alarmEnabled"),
                schedule, from, to, armedNow(schedule, from, to));
    }

    /** Whether the alarm schedule arms the gate at this moment. */
    private boolean armedNow(String schedule, LocalTime from, LocalTime to) {
        ZonedDateTime now = ZonedDateTime.now(clock.zone());
        LocalTime t = now.toLocalTime();
        return switch (schedule) {
            case "OPEN_HOURS" -> {
                LibraryCalendar.Snapshot cal = calendar.snapshot(now.toLocalDate(), now.toLocalDate());
                LocalTime open = cal.openingTime(now.toLocalDate());
                LocalTime close = cal.closingTime(now.toLocalDate());
                yield cal.isOpen(now.toLocalDate()) && open != null && close != null
                        && !t.isBefore(open.minusMinutes(15)) && !t.isAfter(close.plusMinutes(30));
            }
            case "CUSTOM" -> from == null || to == null || (from.isBefore(to)
                    ? !t.isBefore(from) && t.isBefore(to)
                    : !t.isBefore(from) || t.isBefore(to));          // overnight window
            default -> true;
        };
    }

    private static LocalTime parseTime(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalTime.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ------------------------------------------------ device endpoints

    @GetMapping("/api/gate/device/config")
    public GateConfig deviceConfig() {
        return config();
    }

    /**
     * Real-time borrowed/unborrowed check for tags detected at the gate. Unknown EPCs (not library
     * tags) are ignored. In emergency mode the gate never alarms and never blocks people.
     */
    @PostMapping("/api/gate/device/check")
    @Transactional
    public CheckResponse check(@Valid @RequestBody CheckRequest req) {
        GateConfig cfg = config();
        List<TagResult> results = new ArrayList<>();
        boolean alarm = false;
        Instant now = Instant.now();
        for (String raw : req.epcs()) {
            String epc = raw.trim().toUpperCase();
            Item item = items.findByIdentifier(epc).orElse(null);
            if (item == null) continue;
            boolean authorized = item.getStatus() == ItemStatus.ON_LOAN;
            results.add(new TagResult(epc, item.getBarcode(), item.getRecord().getTitle(), item.getStatus(), authorized));
            if (!authorized) {
                GateEvent e = new GateEvent(req.gateId(), GateEventType.ALARM, now);
                e.setItem(item);
                e.setEpc(epc);
                e.setMessage(cfg.emergencyMode() ? "Unborrowed item during emergency mode (alarm suppressed)"
                        : !cfg.armedNow() ? "Unborrowed item outside alarm hours (alarm off)"
                        : "Unborrowed item detected");
                events.save(e);
                alarm = true;
            }
        }
        return new CheckResponse(alarm && cfg.alarmEnabled() && !cfg.emergencyMode() && cfg.armedNow(), cfg.emergencyMode(), results);
    }

    @PostMapping("/api/gate/device/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deviceEvent(@Valid @RequestBody DeviceEventRequest req) {
        GateEvent e = new GateEvent(req.gateId(), req.type(), Instant.now());
        e.setVisitorCount(req.visitorCount());
        e.setMessage(req.message());
        events.save(e);
    }

    // ------------------------------------------------ staff endpoints

    @GetMapping("/api/gate/events")
    @Transactional(readOnly = true)
    public PageResponse<EventDto> list(@RequestParam(required = false) GateEventType type,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        LocalDate t = to == null ? clock.today() : to;
        Instant start = clock.startOfDay(from == null ? t.minusDays(6) : from);
        Instant end = clock.startOfDay(t.plusDays(1));
        PageRequest pr = PageRequest.of(page, Math.min(size, 200));
        Page<GateEvent> result = type == null
                ? events.findByOccurredAtBetweenOrderByOccurredAtDesc(start, end, pr)
                : events.findByEventTypeAndOccurredAtBetweenOrderByOccurredAtDesc(type, start, end, pr);
        return PageResponse.of(result, EventDto::of);
    }

    @GetMapping("/api/gate/summary")
    public Summary summary() {
        Instant start = clock.startOfDay(clock.today());
        Instant end = clock.startOfDay(clock.today().plusDays(1));
        Instant onlineCutoff = Instant.now().minusSeconds(300);
        List<GateStatus> gates = events.lastSeenPerGate().stream()
                .filter(r -> !"system".equals(r[0]))
                .map(r -> new GateStatus((String) r[0], (Instant) r[1], ((Instant) r[1]).isAfter(onlineCutoff)))
                .toList();
        GateConfig cfg = config();
        return new Summary(events.countByEventTypeAndOccurredAtBetween(GateEventType.ALARM, start, end),
                events.sumVisitors(start, end),
                events.countByEventTypeAndOccurredAtBetween(GateEventType.TAMPER, start, end),
                events.countByEventTypeAndOccurredAtBetween(GateEventType.POWER_LOSS, start, end),
                cfg.emergencyMode(), cfg.alarmEnabled(), gates, cfg);
    }

    @PutMapping("/api/gate/settings")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public GateConfig updateSettings(@RequestBody GateConfig req) {
        GateConfig before = config();
        settings.put("gate.emergencyMode", String.valueOf(req.emergencyMode()));
        settings.put("gate.alarmEnabled", String.valueOf(req.alarmEnabled()));
        if (req.schedule() != null) {
            if (!Set.of("ALWAYS", "OPEN_HOURS", "CUSTOM").contains(req.schedule())) {
                throw ApiException.badRequest("Unknown alarm schedule");
            }
            if ("CUSTOM".equals(req.schedule()) && (req.armFrom() == null || req.armTo() == null || req.armFrom().equals(req.armTo()))) {
                throw ApiException.badRequest("Set the start and end of the alarm hours");
            }
            settings.put("gate.schedule", req.schedule());
            if (req.armFrom() != null) settings.put("gate.armFrom", req.armFrom().toString());
            if (req.armTo() != null) settings.put("gate.armTo", req.armTo().toString());
        }
        if (before.emergencyMode() != req.emergencyMode()) {
            GateEvent e = new GateEvent("system", req.emergencyMode() ? GateEventType.EMERGENCY_ON
                    : GateEventType.EMERGENCY_OFF, Instant.now());
            e.setMessage("Set by " + AuditService.currentActor());
            events.save(e);
        }
        audit.record("GATE_SETTINGS", "emergency=" + req.emergencyMode() + " alarm=" + req.alarmEnabled()
                + (req.schedule() == null ? "" : " schedule=" + req.schedule()));
        return config();
    }

    /**
     * Disaster-risk tool: pushes a message to every kiosk/display, which shows it and reads it
     * aloud with text-to-speech.
     */
    @PostMapping("/api/gate/broadcast")
    @Transactional
    public void broadcast(@Valid @RequestBody BroadcastRequest req) {
        Instant now = Instant.now();
        broadcasts.broadcast(req.message(), req.presetId());
        GateEvent e = new GateEvent("system", GateEventType.BROADCAST, now);
        e.setMessage(req.message().trim());
        events.save(e);
        audit.record("BROADCAST", req.message());
    }

    @GetMapping("/api/gate/presets")
    public List<Preset> presets() {
        return broadcasts.presets();
    }

    @PutMapping("/api/gate/presets")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public List<Preset> updatePresets(@RequestBody @Size(max = 12, message = "at most 12 presets") List<@Valid Preset> presets) {
        return broadcasts.update(presets);
    }

    @PostMapping(path = "/api/gate/presets/{id}/audio", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public Preset uploadAudio(@PathVariable String id, @RequestPart("file") MultipartFile file) {
        return broadcasts.attachAudio(id, file);
    }

    @DeleteMapping("/api/gate/presets/{id}/audio")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public Preset removeAudio(@PathVariable String id) {
        return broadcasts.removeAudio(id);
    }

    /** Staff preview of a preset's recording. */
    @GetMapping("/api/gate/presets/{id}/audio")
    public ResponseEntity<Resource> presetAudio(@PathVariable String id) {
        return broadcasts.presetAudio(id).response();
    }

    /** Current announcement for public displays (OPAC kiosks); announcements are public by nature. */
    public record PublicBroadcast(BroadcastService.CurrentBroadcast broadcast, VoiceSettings voice) {
    }

    @GetMapping("/api/opac/broadcast")
    public PublicBroadcast publicBroadcast() {
        return new PublicBroadcast(broadcasts.current(), VoiceSettings.load(settings));
    }

    @GetMapping("/api/opac/broadcast-audio")
    public ResponseEntity<Resource> publicBroadcastAudio() {
        return broadcasts.currentAudio().response();
    }

    /** Recording of the announcement currently being broadcast (kiosks). */
    @GetMapping("/api/kiosk/broadcast-audio")
    public ResponseEntity<Resource> broadcastAudio() {
        return broadcasts.currentAudio().response();
    }

    @GetMapping("/api/gate/voice")
    public VoiceSettings voice() {
        return VoiceSettings.load(settings);
    }

    @PutMapping("/api/gate/voice")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public VoiceSettings updateVoice(@Valid @RequestBody VoiceSettings voice) {
        voice.save(settings);
        audit.record("BROADCAST_VOICE", (voice.name() == null || voice.name().isBlank() ? "default" : voice.name())
                + " / " + voice.lang() + " rate=" + voice.rate() + " pitch=" + voice.pitch() + " x" + voice.repeat());
        return VoiceSettings.load(settings);
    }

    @GetMapping("/api/gate/export")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "csv") String format) {
        List<List<?>> rows = events.findByOccurredAtBetweenOrderByOccurredAtAsc(clock.startOfDay(from),
                        clock.startOfDay(to.plusDays(1))).stream().map(EventDto::of)
                .<List<?>>map(e -> java.util.Arrays.asList(e.occurredAt().atZone(clock.zone()).toLocalDateTime().withNano(0).toString().replace('T', ' '),
                        e.gateId(), e.type(), e.epc(), e.barcode(), e.title(), e.visitorCount(), e.message()))
                .toList();
        return Pdf.export(format, "gate-events-" + from + "-to-" + to, "Gate Events", from + " to " + to,
                List.of("Time", "Gate", "Event", "EPC", "Barcode", "Title", "Visitors", "Message"), rows);
    }
}

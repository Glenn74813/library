package ph.edu.zscmst.library.selfcheck;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.circulation.CirculationDtos.BookingCalendar;
import ph.edu.zscmst.library.circulation.CirculationDtos.HoldDto;
import ph.edu.zscmst.library.circulation.CirculationService;
import ph.edu.zscmst.library.circulation.HoldStatus;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.patron.Patron;

/**
 * Reserving from the catalog kiosk: a patron scans their card or types their card number (and PIN, when the
 * library requires one) to reserve a title for pickup at the desk or book it for a later date. Public like the
 * rest of the catalog; every request re-checks the card, so nothing stays signed in on the kiosk.
 */
@RestController
@RequestMapping("/api/opac/reserve")
public class OpacReserveController {

    public record Who(@NotBlank @Size(max = 64) String card, @Size(max = 20) String pin) {
    }

    public record CalendarRequest(@NotNull @Valid Who patron, @NotNull Long recordId) {
    }

    /** {@code pickupDate} null: reserve the next available copy; a date: book the title for that day. */
    public record ReserveRequest(@NotNull @Valid Who patron, @NotNull Long recordId, LocalDate pickupDate) {
    }

    public record Config(boolean requirePin) {
    }

    /** Only the first name: the kiosk is a public screen. */
    public record Hello(String firstName) {
    }

    public record Reserved(String title, String message, Instant pickupBy, LocalDate pickupDate, Integer queuePosition) {
    }

    private final KioskPatrons kioskPatrons;
    private final CirculationService circulation;
    private final LibraryClock clock;

    public OpacReserveController(KioskPatrons kioskPatrons, CirculationService circulation, LibraryClock clock) {
        this.kioskPatrons = kioskPatrons;
        this.circulation = circulation;
        this.clock = clock;
    }

    @GetMapping("/config")
    public Config config() {
        return new Config(kioskPatrons.requirePin());
    }

    @PostMapping("/identify")
    public Hello identify(@Valid @RequestBody Who who) {
        return new Hello(verify(who).getFirstName());
    }

    @PostMapping("/calendar")
    public BookingCalendar calendar(@Valid @RequestBody CalendarRequest req) {
        return circulation.bookingCalendar(req.recordId(), verify(req.patron()).getId());
    }

    @PostMapping
    public Reserved reserve(@Valid @RequestBody ReserveRequest req) {
        Patron p = verify(req.patron());
        HoldDto h = circulation.placeHold(p.getId(), req.recordId(), req.pickupDate());
        String message;
        if (h.status() == HoldStatus.SCHEDULED) {
            message = "Booked. Collect it at the circulation desk on " + clock.format(clock.endOfDay(h.pickupDate()))
                    + "; it is held for you from that morning.";
        } else if (h.status() == HoldStatus.READY) {
            message = "A copy is being set aside for you. Collect it at the circulation desk"
                    + (h.expiresAt() == null ? "." : " by " + clock.format(h.expiresAt()) + ".");
        } else {
            message = "Reserved. You are number " + h.queuePosition() + " in line; you will be notified when a copy is ready.";
        }
        return new Reserved(h.title(), message, h.expiresAt(), h.pickupDate(), h.queuePosition());
    }

    private Patron verify(Who who) {
        return kioskPatrons.verify(who.card(), who.pin(), "Catalog kiosk");
    }
}

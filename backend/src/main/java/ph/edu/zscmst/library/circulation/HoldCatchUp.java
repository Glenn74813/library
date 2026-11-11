package ph.edu.zscmst.library.circulation;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs the hold jobs once at start-up, so bookings that came due and pickups that expired while
 * the server was down are processed straight away (not only at the next scheduled run).
 */
@Component
class HoldCatchUp {

    private final CirculationService circulation;

    HoldCatchUp(CirculationService circulation) {
        this.circulation = circulation;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        circulation.expireHolds();
        circulation.activateBookings();
    }
}

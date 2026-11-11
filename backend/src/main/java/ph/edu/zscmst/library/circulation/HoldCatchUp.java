package ph.edu.zscmst.library.circulation;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import ph.edu.zscmst.library.attendance.AttendanceAutoTimeout;

/**
 * Runs the scheduled jobs once at start-up, so bookings that came due, pickups that expired and
 * visits left open while the server was down are processed straight away.
 */
@Component
class HoldCatchUp {

    private final CirculationService circulation;
    private final AttendanceAutoTimeout attendance;

    HoldCatchUp(CirculationService circulation, AttendanceAutoTimeout attendance) {
        this.circulation = circulation;
        this.attendance = attendance;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        circulation.expireHolds();
        circulation.activateBookings();
        attendance.closeOpenVisits();
    }
}

package ph.edu.zscmst.library.circulation;

public enum HoldStatus {
    /** Booked for a future pickup date; copies keep circulating until then. */
    SCHEDULED,
    /** Waiting for a copy to come back. */
    PENDING,
    /** A copy is set aside for pickup. */
    READY,
    FULFILLED,
    CANCELLED,
    EXPIRED
}

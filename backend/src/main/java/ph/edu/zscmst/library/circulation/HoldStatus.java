package ph.edu.zscmst.library.circulation;

public enum HoldStatus {
    /** Waiting for a copy to come back. */
    PENDING,
    /** A copy is set aside for pickup. */
    READY,
    FULFILLED,
    CANCELLED,
    EXPIRED
}

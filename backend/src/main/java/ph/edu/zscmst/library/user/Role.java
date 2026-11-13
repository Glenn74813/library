package ph.edu.zscmst.library.user;

/** Staff and device roles. Patrons authenticate separately and receive the PATRON role. */
public enum Role {
    ADMIN,
    LIBRARIAN,
    STAFF,
    /** Receives fine payments: sees only the fines and payments screens. */
    CASHIER,
    /** OPAC / attendance kiosk device account. */
    KIOSK,
    /** RFID security gate device account. */
    GATE
}

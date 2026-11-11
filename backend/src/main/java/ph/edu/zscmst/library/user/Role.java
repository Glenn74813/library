package ph.edu.zscmst.library.user;

/** Staff and device roles. Patrons authenticate separately and receive the PATRON role. */
public enum Role {
    ADMIN,
    LIBRARIAN,
    STAFF,
    /** OPAC / attendance kiosk device account. */
    KIOSK,
    /** RFID security gate device account. */
    GATE
}

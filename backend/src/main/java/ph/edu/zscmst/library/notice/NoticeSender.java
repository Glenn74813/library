package ph.edu.zscmst.library.notice;

/** Delivery channel(s) for patron notices. */
public interface NoticeSender {

    /** Outcome of one delivery attempt. */
    record Delivery(boolean delivered, String channels, String error) {
    }

    Delivery send(Notice notice);
}

package ph.edu.zscmst.library.notice;

/**
 * Delivery channel for patron notices. The default implementation only logs; plug in an
 * e-mail (JavaMailSender) or SMS gateway bean to deliver for real.
 */
public interface NoticeSender {

    /** @return true if the notice was handed to the channel and can be marked sent */
    boolean send(Notice notice);
}

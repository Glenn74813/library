package ph.edu.zscmst.library.sip2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SipMessageTest {

    @Test
    void parsesFixedPartAndFields() {
        SipMessage m = SipMessage.parse("11YN20261111    101500                  AOZSCMST|AA2025-0101|ABLRC-000002|AC|ADpin1|");
        assertEquals("11", m.command());
        assertEquals('Y', m.flag(0, 'N'));
        assertEquals("2025-0101", m.field("AA"));
        assertEquals("LRC-000002", m.field("AB"));
        assertEquals("pin1", m.field("AD"));
        assertNull(m.sequence());
    }

    @Test
    void checksumRoundTrip() {
        String line = new SipMessage.Reply("93").fixed("00").field("CN", "kiosk1").field("CO", "secret").build('4');
        SipMessage m = SipMessage.parse(line);
        assertEquals('4', m.sequence());
        assertEquals("kiosk1", m.field("CN"));
        assertEquals("secret", m.field("CO"));
        String corrupted = line.replace("kiosk1", "kiosk2");
        assertThrows(IllegalArgumentException.class, () -> SipMessage.parse(corrupted));
    }

    @Test
    void checksumMatchesStandardExample() {
        // Example from the SIP2 specification (checksum of everything up to and including "AZ").
        assertEquals("FCA5", SipMessage.checksum("9900302.00AY1AZ"));
    }
}

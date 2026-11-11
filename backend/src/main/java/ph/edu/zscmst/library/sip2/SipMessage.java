package ph.edu.zscmst.library.sip2;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One SIP2 message: a two-digit command, a fixed-length part, then variable fields like {@code AAcard|}.
 * Error detection ({@code AY<seq>AZ<checksum>}) is checked when present and echoed back in replies.
 */
public record SipMessage(String command, String fixed, Map<String, String> fields, Character sequence) {

    private static final Pattern TRAILER = Pattern.compile("\\|?AY(\\d)AZ([0-9A-Fa-f]{4})$");

    /** Length of the fixed part after the command, per request type. */
    private static int fixedLength(String command) {
        return switch (command) {
            case "93" -> 2;
            case "99" -> 8;
            case "23" -> 21;
            case "63" -> 31;
            case "17", "35" -> 18;
            case "11", "29" -> 38;
            case "09" -> 37;
            default -> 0;
        };
    }

    public static SipMessage parse(String raw) {
        if (raw.length() < 2) throw new IllegalArgumentException("Message too short");
        Character seq = null;
        String body = raw;
        Matcher m = TRAILER.matcher(raw);
        if (m.find()) {
            String upToAz = raw.substring(0, m.start(2));
            if (!checksum(upToAz).equalsIgnoreCase(m.group(2))) throw new IllegalArgumentException("Bad checksum");
            seq = m.group(1).charAt(0);
            body = raw.substring(0, m.start()) + (raw.charAt(m.start()) == '|' ? "|" : "");
        }
        String command = body.substring(0, 2);
        int end = Math.min(body.length(), 2 + fixedLength(command));
        Map<String, String> fields = new LinkedHashMap<>();
        for (String part : body.substring(end).split("\\|")) {
            if (part.length() >= 2) fields.putIfAbsent(part.substring(0, 2), part.substring(2));
        }
        return new SipMessage(command, body.substring(2, end), fields, seq);
    }

    public String field(String code) {
        return fields.getOrDefault(code, "");
    }

    /** Character at {@code index} of the fixed part, or {@code fallback} if the message is short. */
    public char flag(int index, char fallback) {
        return index < fixed.length() ? fixed.charAt(index) : fallback;
    }

    /** SIP2 checksum: two's complement of the 16-bit sum of the characters, as 4 uppercase hex digits. */
    public static String checksum(String s) {
        int sum = 0;
        for (char c : s.toCharArray()) sum += c;
        return String.format("%04X", (-sum) & 0xFFFF);
    }

    /** Builds a reply; field values have the separator removed. */
    public static final class Reply {
        private final StringBuilder sb = new StringBuilder();

        public Reply(String command) {
            sb.append(command);
        }

        public Reply fixed(Object value) {
            sb.append(value);
            return this;
        }

        public Reply yn(boolean value) {
            sb.append(value ? 'Y' : 'N');
            return this;
        }

        public Reply field(String code, Object value) {
            // SIP2 is an ASCII protocol: keep the separator and the "·" used in shelf locations out of values.
            sb.append(code).append(value == null ? "" : value.toString().replace("|", "/").replace(" · ", ", ").replace("\r", " ").replace("\n", " "))
                    .append('|');
            return this;
        }

        public Reply optional(String code, Object value) {
            return value == null || value.toString().isEmpty() ? this : field(code, value);
        }

        public String build(Character sequence) {
            if (sequence == null) return sb.toString();
            String withSeq = sb + "AY" + sequence + "AZ";
            return withSeq + checksum(withSeq);
        }
    }
}

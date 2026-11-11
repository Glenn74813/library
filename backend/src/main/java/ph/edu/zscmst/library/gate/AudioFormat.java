package ph.edu.zscmst.library.gate;

/** Audio formats accepted for recorded announcements, recognised by content. */
public enum AudioFormat {
    MP3("audio/mpeg"), WAV("audio/wav"), OGG("audio/ogg"), M4A("audio/mp4"),
    /** What browsers produce when recording from the microphone (Chrome, Edge, Firefox). */
    WEBM("audio/webm");

    private final String contentType;

    AudioFormat(String contentType) {
        this.contentType = contentType;
    }

    public String contentType() { return contentType; }

    public static AudioFormat detect(byte[] h) {
        if (h.length >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') return MP3;
        if (h.length >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) return MP3;   // MPEG frame sync
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E') return WAV;
        if (h.length >= 4 && h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return OGG;
        if (h.length >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return M4A;
        if (h.length >= 4 && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF
                && (h[3] & 0xFF) == 0xA3) return WEBM;   // EBML header
        return null;
    }
}

package ph.edu.zscmst.library.digital;

/** Supported e-book file formats, recognised by their content (not just the file name). */
public enum EbookFormat {
    PDF("application/pdf", ".pdf"),
    EPUB("application/epub+zip", ".epub");

    private final String contentType;
    private final String extension;

    EbookFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() { return contentType; }
    public String extension() { return extension; }

    /** Identifies the format from the first bytes of the file, or returns null if unsupported. */
    public static EbookFormat detect(byte[] head) {
        if (startsWith(head, 0, "%PDF-")) return PDF;
        // EPUB is a ZIP whose first entry is an uncompressed "mimetype" file containing the media type.
        if (startsWith(head, 0, "PK\u0003\u0004") && startsWith(head, 30, "mimetypeapplication/epub+zip")) return EPUB;
        return null;
    }

    private static boolean startsWith(byte[] data, int offset, String prefix) {
        if (data.length < offset + prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (data[offset + i] != (byte) prefix.charAt(i)) return false;
        }
        return true;
    }
}

package ph.edu.zscmst.library.common;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.FontSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fonts for generated PDFs. The built-in PDF fonts only cover Western European letters, so titles and names
 * in other scripts would vanish. The server image installs DejaVu Sans (Latin, Greek, Cyrillic, Arabic, Hebrew…)
 * and WenQuanYi Zen Hei (Chinese, Japanese, Korean); text is set character by character in the first font that
 * has it. Without those fonts (e.g. running from an IDE) it falls back to Helvetica.
 */
public final class PdfFonts {

    private static final Logger log = LoggerFactory.getLogger(PdfFonts.class);

    private static final BaseFont REGULAR = load(List.of("/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"));
    private static final BaseFont BOLD = load(List.of("/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"));
    private static final BaseFont CJK = load(List.of("/usr/share/fonts/wqy-zenhei/wqy-zenhei.ttc,0",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc,0"));

    private PdfFonts() {
    }

    private static BaseFont load(List<String> candidates) {
        for (String c : candidates) {
            String file = c.contains(".ttc,") ? c.substring(0, c.lastIndexOf(',')) : c;
            if (!Files.isReadable(Path.of(file))) continue;
            try {
                return BaseFont.createFont(c, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception e) {
                log.warn("Could not load PDF font {}: {}", c, e.getMessage());
            }
        }
        return null;
    }

    /** A sans-serif font (regular or bold); Helvetica if the Unicode fonts aren't installed. */
    public static Font sans(boolean bold, float size, Color color) {
        BaseFont base = bold ? BOLD : REGULAR;
        if (base == null) return FontFactory.getFont(bold ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, size, Font.NORMAL, color);
        return new Font(base, size, Font.NORMAL, color);
    }

    /** Text in {@code font}, with characters it lacks (e.g. Chinese) taken from the CJK font. */
    public static Phrase phrase(String text, Font font) {
        String s = text == null ? "" : text;
        if (CJK == null || s.chars().allMatch(c -> c < 0x2E80)) return new Phrase(s, font);
        FontSelector selector = new FontSelector();
        selector.addFont(font);
        selector.addFont(new Font(CJK, font.getSize(), font.getStyle(), font.getColor()));
        return selector.process(s);
    }
}

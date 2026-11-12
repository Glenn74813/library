package ph.edu.zscmst.library.common;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Simple printable table reports (landscape A4) with the library header, for "Download PDF". */
public final class Pdf {

    private static final Color NAVY = new Color(14, 14, 120);
    private static final float SHORT_COLUMN = 75;
    private static final Color STRIPE = new Color(243, 244, 251);

    /** Library time zone for the "Generated" stamp; set by {@link LibraryClock}. */
    private static volatile ZoneId zone = ZoneId.systemDefault();

    private Pdf() {
    }

    /** Library name for the report header; set by {@link SystemSettings}. */
    private static volatile java.util.function.Supplier<String> libraryName = () -> "ZSCMST Learning Resource Center";

    static void useLibraryName(java.util.function.Supplier<String> name) {
        libraryName = name;
    }

    static void useZone(ZoneId libraryZone) {
        zone = libraryZone;
    }

    /**
     * Column widths in points: short columns (codes, dates, statuses) keep their natural width so they never
     * break mid-word, and the long text columns share whatever room is left, wrapping as needed.
     */
    private static float[] widths(Font th, Font td, List<String> header, List<List<?>> rows, float available) {
        float[] w = new float[header.size()];
        float total = 0;
        for (int c = 0; c < w.length; c++) {
            float widest = th.getBaseFont().getWidthPoint(header.get(c), th.getSize());
            for (int r = 0; r < Math.min(rows.size(), 500); r++) {
                Object v = rows.get(r).get(c);
                if (v != null) widest = Math.max(widest, td.getBaseFont().getWidthPoint(v.toString(), td.getSize()));
            }
            w[c] = Math.min(widest, 400) + 10;
            total += w[c];
        }
        if (total <= available) return w;
        float fixed = 0;
        float flexible = 0;
        for (float x : w) {
            if (x <= SHORT_COLUMN) fixed += x;
            else flexible += x;
        }
        float room = Math.max(available - fixed, flexible * 0.3f);
        for (int c = 0; c < w.length; c++) {
            if (w[c] > SHORT_COLUMN) w[c] = Math.max(SHORT_COLUMN, w[c] * room / flexible);
        }
        return w;
    }

    public static ResponseEntity<byte[]> download(String filename, String title, String subtitle, List<String> header,
                                                  List<List<?>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font h1 = PdfFonts.sans(true, 14, NAVY);
        Font small = PdfFonts.sans(false, 8, Color.DARK_GRAY);
        Font th = PdfFonts.sans(true, 8, Color.WHITE);
        Font td = PdfFonts.sans(false, 8, Color.BLACK);
        doc.add(new Paragraph(PdfFonts.phrase(libraryName.get(), small)));
        doc.add(new Paragraph(PdfFonts.phrase(title, h1)));
        doc.add(new Paragraph(PdfFonts.phrase((subtitle == null ? "" : subtitle + "  ·  ") + "Generated "
                + LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")) + "  ·  " + rows.size() + " row(s)", small)));
        doc.add(new Paragraph(" ", small));
        PdfPTable table = new PdfPTable(widths(th, td, header, rows, doc.getPageSize().getWidth() - 56));
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String h : header) {
            PdfPCell c = cell(h, th);
            c.setBackgroundColor(NAVY);
            c.setPadding(4);
            table.addCell(c);
        }
        for (int i = 0; i < rows.size(); i++) {
            for (Object v : rows.get(i)) {
                PdfPCell c = cell(v == null ? "" : v.toString(), td);
                c.setPadding(3);
                if (i % 2 == 1) c.setBackgroundColor(STRIPE);
                if (v instanceof Number) c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                table.addCell(c);
            }
        }
        if (rows.isEmpty()) {
            PdfPCell c = cell("No data", td);
            c.setColspan(header.size());
            table.addCell(c);
        }
        doc.add(table);
        doc.close();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(out.toByteArray());
    }

    /** A table cell; left-to-right with bidi on, so Arabic or Hebrew text is joined and ordered correctly. */
    private static PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(PdfFonts.phrase(text, font));
        c.setRunDirection(PdfWriter.RUN_DIRECTION_LTR);
        return c;
    }

    /** CSV (Excel) or PDF of the same table, chosen by {@code format}. */
    public static ResponseEntity<byte[]> export(String format, String baseName, String title, String subtitle,
                                                List<String> header, List<List<?>> rows) {
        return "pdf".equalsIgnoreCase(format)
                ? download(baseName + ".pdf", title, subtitle, header, rows)
                : Csv.download(baseName + ".csv", header, rows);
    }
}

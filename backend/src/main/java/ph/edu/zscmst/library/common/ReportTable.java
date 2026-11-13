package ph.edu.zscmst.library.common;

import java.util.List;

import org.springframework.http.ResponseEntity;

/**
 * One report as rows: downloaded as Excel, CSV or PDF, or previewed on the Reports page, so what staff see on
 * screen is exactly what the file contains.
 */
public record ReportTable(String baseName, String title, String subtitle, List<String> header, List<List<?>> rows) {

    /** First rows of the report for the page, with the total so it can say "showing 100 of 2,300". */
    public record Preview(String title, String subtitle, List<String> header, List<List<String>> rows, int total) {
    }

    public static final int PREVIEW_ROWS = 100;

    /** Codes read as words in every format: ON_LOAN as "On loan", true as "Yes". */
    public ReportTable {
        rows = rows.stream().<List<?>>map(r -> r.stream().map(ReportTable::readable).toList()).toList();
    }

    private static Object readable(Object v) {
        if (v instanceof Enum<?> e) return label(e.name());
        if (v instanceof Boolean b) return b ? "Yes" : "No";
        return v;
    }

    /** "Oct 15, 2026" */
    public static String day(java.time.LocalDate d) {
        return d.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH));
    }

    /** ON_LOAN -> "On loan". */
    public static String label(String code) {
        if (code == null || code.isEmpty()) return code;
        String s = code.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public ResponseEntity<byte[]> download(String format) {
        return Pdf.export(format, baseName, title, subtitle, header, rows);
    }

    public Preview preview() {
        return new Preview(title, subtitle, header, rows.subList(0, Math.min(rows.size(), PREVIEW_ROWS)).stream()
                .map(r -> r.stream().map(v -> v == null ? "" : v.toString()).toList()).toList(), rows.size());
    }
}

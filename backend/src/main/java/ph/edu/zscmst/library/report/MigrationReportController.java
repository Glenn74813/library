package ph.edu.zscmst.library.report;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.common.Pdf;
import ph.edu.zscmst.library.common.PdfFonts;
import ph.edu.zscmst.library.common.SystemSettings;
import ph.edu.zscmst.library.transfer.ImportRunLog;

/**
 * Data migration validation report (bid Term 3): every import of the library's existing records, what came
 * in and what was rejected and why, the totals now in the system, how personal data was handled, and
 * sign-off lines for the supplier and the Learning Resource Center.
 */
@RestController
public class MigrationReportController {

    private static final Color NAVY = new Color(14, 14, 120);

    private final ImportRunLog runs;
    private final JdbcTemplate jdbc;
    private final LibraryClock clock;
    private final SystemSettings system;

    public MigrationReportController(ImportRunLog runs, JdbcTemplate jdbc, LibraryClock clock, SystemSettings system) {
        this.runs = runs;
        this.jdbc = jdbc;
        this.clock = clock;
        this.system = system;
    }

    @GetMapping("/api/reports/migration")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public ResponseEntity<byte[]> report(@RequestParam(defaultValue = "pdf") String format) {
        List<ImportRunLog.Run> list = runs.all();
        if (!"pdf".equalsIgnoreCase(format)) {
            return Pdf.export(format, "migration-validation-" + clock.today(), "Data Migration Validation Report", null,
                    List.of("When", "Data", "File", "Rows in file", "Created", "Matched / updated", "Copies created",
                            "Copies skipped", "Problems", "By"),
                    list.stream().<List<?>>map(r -> Arrays.<Object>asList(clock.format(r.runAt()), label(r.kind()), r.filename(),
                            r.rowsInFile(), r.created(), r.matched(), r.copiesCreated(), r.copiesSkipped(), r.problems().size(),
                            r.runBy())).toList());
        }
        return pdf(list);
    }

    private ResponseEntity<byte[]> pdf(List<ImportRunLog.Run> list) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 40);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font small = PdfFonts.sans(false, 8, Color.DARK_GRAY);
        Font body = PdfFonts.sans(false, 9, Color.BLACK);
        Font bold = PdfFonts.sans(true, 9, Color.BLACK);
        Font h1 = PdfFonts.sans(true, 15, NAVY);
        Font h2 = PdfFonts.sans(true, 11, NAVY);
        var lib = system.profile();

        doc.add(para(lib.name() + (lib.institution() == null ? "" : " · " + lib.institution()), small));
        doc.add(para("Data Migration Validation Report", h1));
        doc.add(para("Generated " + clock.format(java.time.Instant.now()) + " · Smart Library Management System", small));
        doc.add(para(" ", small));

        doc.add(para("1. Imports", h2));
        if (list.isEmpty()) {
            doc.add(para("No imports have been recorded yet.", body));
        } else {
            PdfPTable t = table(new float[] {2.4f, 1.4f, 3f, 1.1f, 1.1f, 1.3f, 1.1f, 1.1f});
            header(t, "When", "Data", "File", "Rows", "Created", "Matched / updated", "Copies", "Skipped");
            for (ImportRunLog.Run r : list) {
                row(t, body, clock.format(r.runAt()), label(r.kind()), r.filename() == null ? "—" : r.filename(),
                        r.rowsInFile(), r.created(), r.matched(), r.copiesCreated(), r.copiesSkipped());
            }
            doc.add(t);
        }

        doc.add(para(" ", small));
        doc.add(para("2. Records now in the system", h2));
        PdfPTable totals = table(new float[] {4f, 1.5f});
        header(totals, "Record", "Count");
        for (Map.Entry<String, Long> e : totals().entrySet()) row(totals, body, e.getKey(), e.getValue());
        doc.add(totals);

        doc.add(para(" ", small));
        doc.add(para("3. Rows not imported, and why", h2));
        List<String> problems = new ArrayList<>();
        for (ImportRunLog.Run r : list) {
            for (String p : r.problems()) problems.add((r.filename() == null ? label(r.kind()) : r.filename()) + ": " + p);
        }
        if (problems.isEmpty()) {
            doc.add(para("None: every row in the imported files was accepted.", body));
        } else {
            for (String p : problems.subList(0, Math.min(problems.size(), 300))) doc.add(para("• " + p, body));
            if (problems.size() > 300) doc.add(para("... and " + (problems.size() - 300) + " more (see the Excel version).", small));
        }

        doc.add(para(" ", small));
        doc.add(para("4. Data privacy (Republic Act No. 10173)", h2));
        for (String line : List.of(
                "The records were imported into the library's own system on the school's server; no personal data was sent to the supplier or other parties.",
                "Only staff accounts with the needed role can see patron data; passwords and PINs are stored encrypted (bcrypt).",
                "Each import is recorded in the audit trail with the account that ran it and the time.",
                "Patron data can be exported for the patron, corrected, or anonymized under Administration → Data privacy, with retention limits.")) {
            doc.add(para("• " + line, body));
        }

        doc.add(para(" ", small));
        doc.add(para("5. Validation and sign-off", h2));
        doc.add(para("The undersigned have compared the records above with the library's source data and confirm that the migration is complete and accurate, except for the rows listed in section 3.", body));
        doc.add(para(" ", small));
        PdfPTable sign = table(new float[] {1f, 1f});
        sign.getDefaultCell().setBorder(PdfPCell.NO_BORDER);
        for (String who : List.of("Prepared by (Supplier)", "Validated by (Learning Resource Center)")) {
            PdfPCell c = new PdfPCell();
            c.setBorder(PdfPCell.NO_BORDER);
            c.addElement(para(" ", body));
            c.addElement(para("______________________________", body));
            c.addElement(para(who, bold));
            c.addElement(para("Name, signature and date", small));
            sign.addCell(c);
        }
        doc.add(sign);
        doc.close();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"migration-validation-" + clock.today() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF).body(out.toByteArray());
    }

    /** Totals of the migrated data as it stands now. */
    private Map<String, Long> totals() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        m.put("Catalog records (titles)", count("select count(*) from bib_record"));
        jdbc.query("select material_type, count(*) from bib_record group by 1 order by 1",
                rs -> { m.put("    " + label(rs.getString(1)), rs.getLong(2)); });
        m.put("Copies (items)", count("select count(*) from item"));
        m.put("    with an RFID tag", count("select count(*) from item where rfid_epc is not null and rfid_epc <> ''"));
        m.put("    with a section", count("select count(*) from item where location is not null"));
        m.put("Patrons", count("select count(*) from patron where anonymized_at is null and card_number not like '\\_\\_%'"));
        m.put("    with a photo", count("select count(*) from patron where photo_key is not null and anonymized_at is null"));
        m.put("Authority headings", count("select count(*) from authority"));
        return m;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static String label(String code) {
        if (code == null) return "";
        if (code.equals("PATRONS")) return "Patrons";
        if (code.equals("CATALOG")) return "Catalog";
        String s = code.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Paragraph para(String text, Font f) {
        return new Paragraph(PdfFonts.phrase(text, f));
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        return t;
    }

    private static void header(PdfPTable t, String... names) {
        Font th = PdfFonts.sans(true, 8, Color.WHITE);
        for (String n : names) {
            PdfPCell c = new PdfPCell(PdfFonts.phrase(n, th));
            c.setBackgroundColor(NAVY);
            c.setPadding(4);
            t.addCell(c);
        }
    }

    private static void row(PdfPTable t, Font f, Object... values) {
        for (Object v : values) {
            PdfPCell c = new PdfPCell(PdfFonts.phrase(String.valueOf(v), f));
            c.setPadding(3);
            if (v instanceof Number) c.setHorizontalAlignment(Element.ALIGN_RIGHT);
            t.addCell(c);
        }
    }
}

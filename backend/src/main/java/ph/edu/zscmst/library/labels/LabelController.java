package ph.edu.zscmst.library.labels;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.Barcode128;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfWriter;

import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.catalog.ItemRepository;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.common.SystemSettings;
import ph.edu.zscmst.library.digital.FileStorage;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronRepository;
import ph.edu.zscmst.library.printlog.PrintLogService;
import ph.edu.zscmst.library.printlog.PrintLogService.Kind;

/**
 * Printable PDFs on A4 label sheets: item barcode labels (Avery L7160, 21 per sheet), spine
 * labels (Avery L7651, 65 per sheet) and patron ID cards (CR80 bank-card size, 10 per sheet).
 */
@RestController
public class LabelController {

    private static final float MM = 72f / 25.4f;
    private static final Color NAVY = new Color(14, 14, 120);
    private static final Color GOLD = new Color(232, 182, 32);
    private static final int MAX_LABELS = 2000;

    /** A label sheet: columns x rows of w x h mm, first label at (left, top) mm, then every pitch mm. */
    record Sheet(int cols, int rows, float w, float h, float left, float top, float pitchX, float pitchY) {
        static final Sheet L7160 = new Sheet(3, 7, 63.5f, 38.1f, 7.2f, 15.1f, 66.0f, 38.1f);
        static final Sheet L7651 = new Sheet(5, 13, 38.1f, 21.2f, 4.7f, 10.7f, 40.6f, 21.2f);
        static final Sheet CR80 = new Sheet(2, 5, 85.6f, 54f, 15f, 10f, 92f, 57f);

        int perPage() {
            return cols * rows;
        }

        /** Lower-left corner of label {@code i} on its page, in points. */
        float[] origin(int i) {
            int n = i % perPage();
            float x = (left + (n % cols) * pitchX) * MM;
            float yTop = PageSize.A4.getHeight() - (top + (n / cols) * pitchY) * MM;
            return new float[] {x, yTop - h * MM};
        }
    }

    private interface Painter<T> {
        void paint(PdfContentByte cb, PdfWriter writer, T value, float x, float y) throws IOException;
    }

    private final ItemRepository items;
    private final PatronRepository patrons;
    private final FileStorage storage;
    private final SystemSettings system;
    private final LibraryClock clock;
    private final AuditService audit;
    private final PrintLogService printLog;

    public LabelController(ItemRepository items, PatronRepository patrons, FileStorage storage, SystemSettings system,
                           LibraryClock clock, AuditService audit, PrintLogService printLog) {
        this.items = items;
        this.patrons = patrons;
        this.storage = storage;
        this.system = system;
        this.clock = clock;
        this.audit = audit;
        this.printLog = printLog;
    }

    /**
     * Item labels for the listed barcodes, all copies of a record, or copies added since a date.
     * {@code kind} is "barcode" or "spine"; {@code skip} leaves that many labels empty to reuse a partly used sheet.
     */
    @GetMapping("/api/labels/items")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> itemLabels(@RequestParam(defaultValue = "") String barcodes,
                                             @RequestParam(required = false) Long recordId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate addedSince,
                                             @RequestParam(defaultValue = "barcode") String kind,
                                             @RequestParam(defaultValue = "0") int skip) {
        List<Item> list = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        Arrays.stream(barcodes.split("[,\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).distinct().forEach(code -> {
            Optional<Item> i = items.findByIdentifier(code);
            if (i.isPresent()) list.add(i.get());
            else unknown.add(code);
        });
        if (recordId != null) {
            // Skip copies already listed by barcode, so each label prints once.
            Set<Long> listed = list.stream().map(Item::getId).collect(Collectors.toSet());
            items.findByRecordIdOrderByBarcodeAsc(recordId).stream().filter(i -> !listed.contains(i.getId())).forEach(list::add);
        }
        if (addedSince != null) list.addAll(items.findByCreatedAtGreaterThanEqualOrderByBarcodeAsc(clock.startOfDay(addedSince)));
        if (!unknown.isEmpty()) throw ApiException.badRequest("Unknown barcode(s): " + String.join(", ", unknown));
        if (list.isEmpty()) throw ApiException.badRequest("No copies match; enter barcodes, a record, or a date");
        if (list.size() > MAX_LABELS) throw ApiException.badRequest("Too many labels at once (max " + MAX_LABELS + ")");
        boolean spine = "spine".equalsIgnoreCase(kind);
        byte[] pdf = render(spine ? Sheet.L7651 : Sheet.L7160, list, skip, spine ? this::spineLabel : this::barcodeLabel);
        audit.record("LABELS_PRINTED", list.size() + " " + (spine ? "spine" : "barcode") + " label(s)");
        return file(pdf, (spine ? "spine" : "barcode") + "-labels.pdf");
    }

    /** Patron ID cards for listed card numbers, or by category / program / registered since. */
    @GetMapping("/api/labels/patron-cards")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> patronCards(@RequestParam(defaultValue = "") String cards,
                                              @RequestParam(required = false) Long categoryId,
                                              @RequestParam(required = false) Long courseId,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate registeredSince,
                                              @RequestParam(defaultValue = "0") int skip) {
        List<Patron> list = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> codes = Arrays.stream(cards.split("[,\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        codes.forEach(code -> {
            Optional<Patron> p = patrons.findByIdentifier(code).filter(x -> x.getAnonymizedAt() == null);
            if (p.isPresent()) list.add(p.get());
            else unknown.add(code);
        });
        if (codes.isEmpty() && (categoryId != null || courseId != null || registeredSince != null)) {
            list.addAll(patrons.forCards(categoryId, courseId,
                    registeredSince == null ? Instant.EPOCH : clock.startOfDay(registeredSince)));
        }
        if (!unknown.isEmpty()) throw ApiException.badRequest("Unknown card number(s): " + String.join(", ", unknown));
        if (list.isEmpty()) throw ApiException.badRequest("No patrons match; enter card numbers or choose a filter");
        if (list.size() > MAX_LABELS) throw ApiException.badRequest("Too many cards at once (max " + MAX_LABELS + ")");
        byte[] pdf = render(Sheet.CR80, list, skip, this::patronCard);
        audit.record("ID_CARDS_PRINTED", list.size() + " patron card(s)");
        list.forEach(p -> printLog.record(Kind.ID_CARD, p.getId(), p.getCardNumber(), "PDF generated", false));
        return file(pdf, "patron-id-cards.pdf");
    }

    private <T> byte[] render(Sheet sheet, List<T> values, int skip, Painter<T> painter) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 0, 0, 0, 0);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        doc.open();
        PdfContentByte cb = writer.getDirectContent();
        int offset = Math.clamp(skip, 0, sheet.perPage() - 1);
        try {
            for (int i = 0; i < values.size(); i++) {
                int slot = i + offset;
                if (slot > 0 && slot % sheet.perPage() == 0) doc.newPage();
                float[] o = sheet.origin(slot);
                painter.paint(cb, writer, values.get(i), o[0], o[1]);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not draw labels", e);
        }
        doc.close();
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ painters

    private static Font font(String name, float size, Color color) {
        return FontFactory.getFont(name, size, Font.NORMAL, color);
    }

    private static void text(PdfContentByte cb, String s, Font f, float x, float y, int align) {
        ColumnText.showTextAligned(cb, align, new Phrase(s == null ? "" : s, f), x, y, 0);
    }

    /** Wrapped text inside a box (top-left x, top y). */
    private static void box(PdfContentByte cb, String s, Font f, float x, float yTop, float w, float h, int align) {
        ColumnText ct = new ColumnText(cb);
        ct.setSimpleColumn(new Phrase(s == null ? "" : s, f), x, yTop - h, x + w, yTop, f.getSize() * 1.15f, align);
        ct.go();
    }

    private static Image barcode(PdfContentByte cb, String code, float barHeight, float width) {
        // Code 128 only encodes ASCII: say which code is the problem instead of failing the whole sheet.
        if (!code.chars().allMatch(c -> c >= 32 && c < 127)) {
            throw ApiException.badRequest("Can't print a barcode for \"" + code + "\": use letters, digits and symbols only");
        }
        Barcode128 b = new Barcode128();
        b.setCode(code);
        b.setBarHeight(barHeight);
        b.setFont(null);
        Image img = b.createImageWithBarcode(cb, Color.BLACK, Color.BLACK);
        img.scaleToFit(width, barHeight);
        return img;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private void barcodeLabel(PdfContentByte cb, PdfWriter w, Item item, float x, float y) {
        float lw = Sheet.L7160.w() * MM;
        float lh = Sheet.L7160.h() * MM;
        float pad = 3 * MM;
        Font small = font(FontFactory.HELVETICA, 6.5f, Color.DARK_GRAY);
        Font title = font(FontFactory.HELVETICA_BOLD, 7.5f, Color.BLACK);
        Font code = font(FontFactory.COURIER_BOLD, 9, Color.BLACK);
        text(cb, system.profile().name(), small, x + pad, y + lh - pad - 5, Element.ALIGN_LEFT);
        box(cb, clip(item.getRecord().getTitle(), 70), title, x + pad, y + lh - pad - 8, lw - 2 * pad, 18, Element.ALIGN_LEFT);
        text(cb, item.getRecord().getCallNumber(), small, x + pad, y + lh - pad - 31, Element.ALIGN_LEFT);
        Image img = barcode(cb, item.getBarcode(), 12 * MM, lw - 2 * pad);
        img.setAbsolutePosition(x + (lw - img.getScaledWidth()) / 2, y + pad + 9);
        try {
            cb.addImage(img);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        text(cb, item.getBarcode(), code, x + lw / 2, y + pad, Element.ALIGN_CENTER);
    }

    private void spineLabel(PdfContentByte cb, PdfWriter w, Item item, float x, float y) {
        float lw = Sheet.L7651.w() * MM;
        float lh = Sheet.L7651.h() * MM;
        String call = item.getRecord().getCallNumber();
        String[] lines = (call == null || call.isBlank() ? item.getBarcode() : call).trim().split("\\s+");
        Font f = font(FontFactory.HELVETICA_BOLD, lines.length > 3 ? 8 : 10, Color.BLACK);
        float lead = f.getSize() * 1.1f;
        float start = y + lh / 2 + (lines.length - 1) * lead / 2 - f.getSize() / 3;
        for (int i = 0; i < lines.length && i < 5; i++) {
            text(cb, lines[i], f, x + lw / 2, start - i * lead, Element.ALIGN_CENTER);
        }
    }

    private void patronCard(PdfContentByte cb, PdfWriter w, Patron p, float x, float y) throws IOException {
        float cw = Sheet.CR80.w() * MM;
        float ch = Sheet.CR80.h() * MM;
        SystemSettings.LibraryProfile lib = system.profile();
        // Card outline (cut line) and navy header with a gold rule.
        cb.saveState();
        cb.setColorStroke(new Color(180, 180, 190));
        cb.setLineWidth(0.4f);
        cb.roundRectangle(x, y, cw, ch, 3 * MM);
        cb.stroke();
        cb.setColorFill(NAVY);
        cb.rectangle(x + 0.3f, y + ch - 13 * MM, cw - 0.6f, 13 * MM - 0.3f);
        cb.fill();
        cb.setColorFill(GOLD);
        cb.rectangle(x + 0.3f, y + ch - 13.8f * MM, cw - 0.6f, 0.8f * MM);
        cb.fill();
        cb.restoreState();
        try (InputStream in = getClass().getResourceAsStream("/pdf/logo.png")) {
            if (in != null) {
                Image logo = Image.getInstance(in.readAllBytes());
                logo.scaleToFit(10 * MM, 10 * MM);
                logo.setAbsolutePosition(x + 2 * MM, y + ch - 11.5f * MM);
                cb.addImage(logo);
            }
        } catch (Exception e) {
            // logo is decoration only
        }
        Font head = font(FontFactory.HELVETICA_BOLD, 7.5f, Color.WHITE);
        Font headSmall = font(FontFactory.HELVETICA, 5.5f, Color.WHITE);
        box(cb, lib.name(), head, x + 13.5f * MM, y + ch - 2 * MM, cw - 15.5f * MM, 10, Element.ALIGN_LEFT);
        box(cb, clip(lib.institution(), 70), headSmall, x + 13.5f * MM, y + ch - 6.2f * MM, cw - 15.5f * MM, 14, Element.ALIGN_LEFT);
        text(cb, "LIBRARY CARD", font(FontFactory.HELVETICA_BOLD, 5.5f, GOLD), x + cw - 2 * MM, y + ch - 12 * MM, Element.ALIGN_RIGHT);

        // Photo (or an empty frame to paste one).
        float px = x + 3 * MM;
        float py = y + 14 * MM;
        float pw = 20 * MM;
        float ph = 24 * MM;
        Image photo = null;
        if (p.getPhotoKey() != null) {
            try {
                photo = Image.getInstance(Files.readAllBytes(storage.path(p.getPhotoKey())));
            } catch (Exception e) {
                photo = null; // e.g. WebP, which PDF can't embed: leave the frame
            }
        }
        if (photo != null) {
            photo.scaleToFit(pw, ph);
            photo.setAbsolutePosition(px + (pw - photo.getScaledWidth()) / 2, py + (ph - photo.getScaledHeight()) / 2);
            try {
                cb.addImage(photo);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        } else {
            cb.saveState();
            cb.setColorStroke(new Color(200, 200, 210));
            cb.rectangle(px, py, pw, ph);
            cb.stroke();
            cb.restoreState();
            text(cb, "PHOTO", font(FontFactory.HELVETICA, 6, Color.GRAY), px + pw / 2, py + ph / 2, Element.ALIGN_CENTER);
        }

        float tx = x + 26 * MM;
        float tw = cw - 28 * MM;
        Font name = font(FontFactory.HELVETICA_BOLD, 9.5f, Color.BLACK);
        Font label = font(FontFactory.HELVETICA, 5.5f, Color.GRAY);
        Font value = font(FontFactory.HELVETICA_BOLD, 7, Color.BLACK);
        box(cb, p.getLastName().toUpperCase() + ", " + p.getFirstName(), name, tx, y + ch - 15.5f * MM, tw, 22, Element.ALIGN_LEFT);
        float row = y + ch - 24.5f * MM;
        text(cb, "CARD NO.", label, tx, row, Element.ALIGN_LEFT);
        text(cb, p.getCardNumber(), value, tx, row - 8, Element.ALIGN_LEFT);
        String group = p.getCategory().getName() + (p.getCourse() == null ? "" : " · " + p.getCourse().getCode());
        text(cb, "TYPE", label, tx, row - 18, Element.ALIGN_LEFT);
        text(cb, group, value, tx, row - 26, Element.ALIGN_LEFT);
        if (p.getExpiryDate() != null) {
            text(cb, "VALID UNTIL", label, tx + tw, row - 18, Element.ALIGN_RIGHT);
            text(cb, p.getExpiryDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy")), value, tx + tw, row - 26, Element.ALIGN_RIGHT);
        }
        Image img = barcode(cb, p.getCardNumber(), 8 * MM, tw);
        img.setAbsolutePosition(tx + (tw - img.getScaledWidth()) / 2, y + 4 * MM);
        try {
            cb.addImage(img);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        text(cb, lib.address(), font(FontFactory.HELVETICA, 5, Color.GRAY), x + cw / 2, y + 1.5f * MM, Element.ALIGN_CENTER);
    }

    private static ResponseEntity<byte[]> file(byte[] pdf, String name) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_PDF).body(pdf);
    }
}

package ph.edu.zscmst.library.transfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.marc4j.MarcException;
import org.marc4j.MarcPermissiveStreamReader;
import org.marc4j.MarcReader;
import org.marc4j.MarcStreamWriter;
import org.marc4j.MarcWriter;
import org.marc4j.MarcXmlReader;
import org.marc4j.MarcXmlWriter;
import org.marc4j.marc.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.catalog.BibRecord;
import ph.edu.zscmst.library.catalog.BibRecordRepository;
import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.catalog.ItemRepository;
import ph.edu.zscmst.library.catalog.MaterialType;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.Csv;

/**
 * Catalog migration: imports MARC21 (ISO 2709 .mrc), MARCXML or CSV with a dry-run preview and a
 * validation report, and exports the catalog as MARC21 or MARCXML with holdings in 952.
 */
@Service
public class CatalogTransferService {

    public static final List<String> CSV_COLUMNS = List.of("title", "subtitle", "authors", "isbn", "publisher", "year",
            "edition", "subjects", "callNumber", "materialType", "language", "summary", "barcode", "location", "aisle", "shelf");

    public record PreviewRow(String source, String title, String authors, String isbn, MaterialType materialType,
                             int copies, String action) {
    }

    public record ImportReport(boolean committed, int titlesInFile, int titlesCreated, int titlesMatched,
                               int copiesCreated, int copiesSkipped, List<String> problems, List<PreviewRow> sample) {
    }

    private final BibRecordRepository records;
    private final ItemRepository items;
    private final AuditService audit;

    public CatalogTransferService(BibRecordRepository records, ItemRepository items, AuditService audit) {
        this.records = records;
        this.items = items;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ import

    @Transactional
    public ImportReport importFile(String filename, byte[] data, boolean commit) {
        List<String> problems = new ArrayList<>();
        List<ParsedTitle> titles = parse(filename, data, problems);
        int created = 0, matched = 0, copiesCreated = 0, copiesSkipped = 0;
        List<PreviewRow> sample = new ArrayList<>();
        Set<String> seenBarcodes = new HashSet<>();
        Map<String, BibRecord> createdInThisRun = new LinkedHashMap<>();

        for (ParsedTitle t : titles) {
            if (t.title == null) {
                problems.add(t.source + ": no title (245$a / title column) — skipped");
                continue;
            }
            Optional<BibRecord> existing = findExisting(t);
            String key = matchKey(t);
            BibRecord target = existing.orElse(createdInThisRun.get(key));
            String action;
            if (target != null) {
                matched++;
                action = "adds copies to existing title";
            } else {
                created++;
                action = "new title";
                if (commit) {
                    target = records.save(toRecord(t));
                    createdInThisRun.put(key, target);
                } else {
                    createdInThisRun.put(key, new BibRecord());   // placeholder so later rows count as matches
                }
            }
            int added = 0;
            for (ParsedTitle.Copy c : t.copies) {
                String bc = c.barcode().trim();
                if (bc.length() > 50) {
                    problems.add(t.source + ": barcode too long (" + bc + ") — copy skipped");
                    copiesSkipped++;
                } else if (!seenBarcodes.add(bc.toLowerCase(Locale.ROOT)) || items.existsByBarcodeIgnoreCase(bc)) {
                    problems.add(t.source + ": barcode " + bc + " already exists — copy skipped");
                    copiesSkipped++;
                } else {
                    if (commit) {
                        Item item = new Item(target, bc);
                        item.setLocation(trim(c.location(), 100));
                        item.setAisle(trim(c.aisle(), 20));
                        item.setShelf(trim(c.shelf(), 20));
                        item.setAcquiredDate(LocalDate.now());
                        items.save(item);
                    }
                    copiesCreated++;
                    added++;
                }
            }
            if (t.copies.isEmpty()) problems.add(t.source + ": \"" + t.title + "\" has no copies (no 952/852 barcode)");
            if (sample.size() < 15) {
                sample.add(new PreviewRow(t.source, t.title, t.authors, t.isbn, t.materialType, added, action));
            }
        }
        if (problems.size() > 200) {
            int more = problems.size() - 200;
            problems = new ArrayList<>(problems.subList(0, 200));
            problems.add("... and " + more + " more");
        }
        if (commit) {
            audit.record("CATALOG_IMPORT", filename + ": " + created + " new titles, " + matched + " matched, "
                    + copiesCreated + " copies, " + copiesSkipped + " skipped");
        }
        return new ImportReport(commit, titles.size(), created, matched, copiesCreated, copiesSkipped, problems, sample);
    }

    private Optional<BibRecord> findExisting(ParsedTitle t) {
        if (t.isbn != null) {
            Optional<BibRecord> byIsbn = records.findFirstByIsbn(t.isbn);
            if (byIsbn.isPresent()) return byIsbn;
        }
        return t.authors == null ? records.findFirstByTitleIgnoreCaseAndAuthorsIsNull(t.title)
                : records.findFirstByTitleIgnoreCaseAndAuthorsIgnoreCase(t.title, t.authors);
    }

    private static String matchKey(ParsedTitle t) {
        return t.isbn != null ? "isbn:" + t.isbn : ("t:" + t.title + "|" + t.authors).toLowerCase(Locale.ROOT);
    }

    private static BibRecord toRecord(ParsedTitle t) {
        BibRecord r = new BibRecord();
        r.setTitle(trim(t.title, 500));
        r.setSubtitle(trim(t.subtitle, 500));
        r.setAuthors(trim(t.authors, 500));
        r.setIsbn(trim(t.isbn, 20));
        r.setPublisher(trim(t.publisher, 255));
        r.setPublicationYear(t.publicationYear);
        r.setEdition(trim(t.edition, 60));
        r.setSubjects(trim(t.subjects, 1000));
        r.setCallNumber(trim(t.callNumber, 100));
        r.setMaterialType(t.materialType == null ? MaterialType.BOOK : t.materialType);
        r.setLanguage(trim(t.language, 40));
        r.setSummary(t.summary);
        return r;
    }

    // ------------------------------------------------------------------ parsing

    List<ParsedTitle> parse(String filename, byte[] data, List<String> problems) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String head = new String(data, 0, Math.min(data.length, 200), StandardCharsets.UTF_8).stripLeading();
        if (name.endsWith(".csv") || (!head.startsWith("<") && head.toLowerCase(Locale.ROOT).startsWith("title"))) {
            return parseCsv(new String(data, StandardCharsets.UTF_8), problems);
        }
        MarcReader reader = head.startsWith("<")
                ? new MarcXmlReader(new ByteArrayInputStream(data))
                : new MarcPermissiveStreamReader(new ByteArrayInputStream(data), true, true);
        List<ParsedTitle> out = new ArrayList<>();
        int n = 0;
        while (true) {
            n++;
            try {
                if (!reader.hasNext()) break;
                Record r = reader.next();
                ParsedTitle t = MarcMapper.toTitle(r);
                t.source = "record " + n;
                out.add(t);
            } catch (MarcException e) {
                problems.add("record " + n + ": unreadable MARC (" + e.getMessage() + ")");
                if (head.startsWith("<")) break;      // XML errors are not recoverable
            }
        }
        if (out.isEmpty() && problems.isEmpty()) {
            throw ApiException.badRequest("No records found. Upload MARC21 (.mrc), MARCXML (.xml) or CSV.");
        }
        return out;
    }

    private List<ParsedTitle> parseCsv(String text, List<String> problems) {
        List<List<String>> rows = Csv.parse(text);
        if (rows.isEmpty()) throw ApiException.badRequest("The file is empty");
        List<String> header = rows.getFirst().stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        if (!header.contains("title")) throw ApiException.badRequest("CSV needs a 'title' column (download the template)");
        Map<String, ParsedTitle> byKey = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            int line = i + 1;
            ParsedTitle t = new ParsedTitle();
            t.title = col(header, row, "title");
            t.subtitle = col(header, row, "subtitle");
            t.authors = col(header, row, "authors");
            String isbn = col(header, row, "isbn");
            t.isbn = isbn == null ? null : isbn.replaceAll("[^0-9Xx]", "").toUpperCase(Locale.ROOT);
            if (t.isbn != null && t.isbn.isEmpty()) t.isbn = null;
            t.publisher = col(header, row, "publisher");
            String year = col(header, row, "year");
            if (year != null) {
                try {
                    t.publicationYear = Integer.parseInt(year.replaceAll("\\D", "").substring(0, Math.min(4, year.replaceAll("\\D", "").length())));
                } catch (RuntimeException e) {
                    problems.add("line " + line + ": year '" + year + "' is not a number — ignored");
                }
            }
            t.edition = col(header, row, "edition");
            t.subjects = col(header, row, "subjects");
            t.callNumber = col(header, row, "callnumber");
            String type = col(header, row, "materialtype");
            if (type != null) {
                try {
                    t.materialType = MaterialType.valueOf(type.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    problems.add("line " + line + ": unknown materialType '" + type + "' — using BOOK");
                }
            }
            t.language = col(header, row, "language");
            t.summary = col(header, row, "summary");
            t.source = "line " + line;
            ParsedTitle target = byKey.computeIfAbsent(matchKey(t), k -> t);
            String barcode = col(header, row, "barcode");
            if (barcode != null) target.copies.add(new ParsedTitle.Copy(barcode, col(header, row, "location"),
                    col(header, row, "aisle"), col(header, row, "shelf")));
        }
        return new ArrayList<>(byKey.values());
    }

    private static String col(List<String> header, List<String> row, String name) {
        int i = header.indexOf(name.toLowerCase(Locale.ROOT));
        if (i < 0 || i >= row.size()) return null;
        String v = row.get(i).trim();
        return v.isEmpty() ? null : v;
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ------------------------------------------------------------------ export

    @Transactional(readOnly = true)
    public byte[] exportMarc(boolean xml) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MarcWriter writer = xml ? new MarcXmlWriter(out, "UTF-8", true) : new MarcStreamWriter(out, "UTF-8");
        for (BibRecord b : records.findAllByOrderByIdAsc()) {
            writer.write(MarcMapper.toMarc(b, items.findByRecordIdOrderByBarcodeAsc(b.getId())));
        }
        writer.close();
        return out.toByteArray();
    }

}

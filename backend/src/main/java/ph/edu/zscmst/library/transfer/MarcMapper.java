package ph.edu.zscmst.library.transfer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.marc4j.marc.ControlField;
import org.marc4j.marc.DataField;
import org.marc4j.marc.MarcFactory;
import org.marc4j.marc.Record;
import org.marc4j.marc.Subfield;
import org.marc4j.marc.VariableField;

import ph.edu.zscmst.library.catalog.BibRecord;
import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.catalog.MaterialType;

/**
 * Maps MARC21 bibliographic records to catalog titles and back. Holdings are read from 952 (Koha:
 * $p barcode, $c shelving location) or 852 ($p barcode, $b/$c location) and written as 952.
 */
public final class MarcMapper {

    private static final Pattern YEAR = Pattern.compile("(1[5-9]\\d{2}|2[01]\\d{2})");
    private static final Pattern ISBN = Pattern.compile("([0-9Xx][0-9Xx\\- ]{8,16}[0-9Xx])");
    private static final Map<String, String> LANGUAGES = Map.of("eng", "English", "fil", "Filipino", "tgl", "Tagalog",
            "spa", "Spanish", "ceb", "Cebuano", "jpn", "Japanese", "chi", "Chinese", "fre", "French", "ger", "German");

    private MarcMapper() {
    }

    public static ParsedTitle toTitle(Record r) {
        ParsedTitle t = new ParsedTitle();
        t.title = clean(sub(r, "245", 'a'));
        t.subtitle = clean(sub(r, "245", 'b'));
        String part = clean(join(r, "245", 'n', 'p'));
        if (part != null) t.title = t.title == null ? part : t.title + ". " + part;
        t.isbn = isbn(r);
        List<String> authors = new ArrayList<>();
        for (String tag : List.of("100", "110", "111", "700", "710")) {
            for (VariableField f : r.getVariableFields(tag)) {
                String a = clean(((DataField) f).getSubfieldsAsString("a"));
                if (a != null) authors.add(a);
            }
        }
        t.authors = authors.isEmpty() ? null : String.join("; ", new LinkedHashSet<>(authors));
        String pubTag = r.getVariableField("264") != null ? "264" : "260";
        t.publisher = clean(sub(r, pubTag, 'b'));
        String date = sub(r, pubTag, 'c');
        ControlField f008 = (ControlField) r.getVariableField("008");
        if (date == null && f008 != null && f008.getData().length() >= 11) date = f008.getData().substring(7, 11);
        if (date != null) {
            Matcher m = YEAR.matcher(date);
            if (m.find()) t.publicationYear = Integer.parseInt(m.group(1));
        }
        t.edition = clean(sub(r, "250", 'a'));
        Set<String> subjects = new LinkedHashSet<>();
        for (String tag : List.of("600", "610", "650", "651", "655")) {
            for (VariableField v : r.getVariableFields(tag)) {
                DataField d = (DataField) v;
                List<String> parts = new ArrayList<>();
                for (Subfield s : d.getSubfields()) {
                    if ("avxyz".indexOf(s.getCode()) >= 0) parts.add(clean(s.getData()));
                }
                parts.removeIf(java.util.Objects::isNull);
                if (!parts.isEmpty()) subjects.add(String.join(" -- ", parts));
            }
        }
        t.subjects = subjects.isEmpty() ? null : truncate(String.join("; ", subjects), 1000);
        t.callNumber = firstNonNull(join(r, "090", 'a', 'b'), join(r, "050", 'a', 'b'), join(r, "082", 'a', 'b'),
                join(r, "092", 'a', 'b'));
        if (f008 != null && f008.getData().length() >= 38) {
            String code = f008.getData().substring(35, 38).trim();
            if (!code.isBlank()) t.language = LANGUAGES.getOrDefault(code, code);
        }
        t.summary = clean(sub(r, "520", 'a'));
        t.materialType = materialType(r);
        for (VariableField v : r.getVariableFields("952")) {
            DataField d = (DataField) v;
            String barcode = clean(d.getSubfieldsAsString("p"));
            if (barcode != null) t.copies.add(new ParsedTitle.Copy(barcode,
                    firstNonNull(clean(d.getSubfieldsAsString("c")), clean(d.getSubfieldsAsString("a"))), null, null));
            if (t.callNumber == null) t.callNumber = clean(d.getSubfieldsAsString("o"));
        }
        for (VariableField v : r.getVariableFields("852")) {
            DataField d = (DataField) v;
            String barcode = clean(d.getSubfieldsAsString("p"));
            if (barcode != null) t.copies.add(new ParsedTitle.Copy(barcode,
                    firstNonNull(clean(d.getSubfieldsAsString("c")), clean(d.getSubfieldsAsString("b"))), null, null));
            if (t.callNumber == null) t.callNumber = clean(join(d, 'h', 'i'));
        }
        return t;
    }

    static MaterialType materialType(Record r) {
        String leader = r.getLeader().marshal();
        char type = leader.length() > 6 ? leader.charAt(6) : 'a';
        char level = leader.length() > 7 ? leader.charAt(7) : 'm';
        if (r.getVariableField("502") != null) return MaterialType.THESIS;
        return switch (type) {
            case 'g', 'i', 'j', 'k', 'o', 'r' -> MaterialType.AUDIOVISUAL;
            case 'm' -> MaterialType.EBOOK;
            case 'e', 'f', 'p' -> MaterialType.OTHER;
            default -> level == 's' || level == 'b' ? MaterialType.SERIAL : MaterialType.BOOK;
        };
    }

    /** Writes a catalog title (with its copies as 952 fields) as a MARC21 record. */
    public static Record toMarc(BibRecord b, List<Item> items) {
        MarcFactory f = MarcFactory.newInstance();
        char type = switch (b.getMaterialType()) {
            case AUDIOVISUAL -> 'g';
            case EBOOK -> 'm';
            case OTHER -> 'r';
            default -> 'a';
        };
        char level = b.getMaterialType() == MaterialType.SERIAL ? 's' : 'm';
        Record r = f.newRecord("00000n" + type + level + " a2200000 a 4500");
        r.addVariableField(f.newControlField("001", "ZSCMST" + b.getId()));
        String year = b.getPublicationYear() == null ? "    " : String.valueOf(b.getPublicationYear());
        String lang = LANGUAGES.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(b.getLanguage() == null ? "" : b.getLanguage()))
                .map(Map.Entry::getKey).findFirst().orElse("   ");
        r.addVariableField(f.newControlField("008", "000000s" + year + "    xx            000 0 " + lang + " d"));
        if (b.getIsbn() != null) r.addVariableField(df(f, "020", ' ', ' ', 'a', b.getIsbn()));
        if (b.getCallNumber() != null) r.addVariableField(df(f, "090", ' ', ' ', 'a', b.getCallNumber()));
        List<String> authors = b.getAuthors() == null ? List.of() : List.of(b.getAuthors().split("\\s*;\\s*"));
        if (!authors.isEmpty()) r.addVariableField(df(f, "100", '1', ' ', 'a', authors.getFirst()));
        DataField title = f.newDataField("245", authors.isEmpty() ? '0' : '1', '0');
        title.addSubfield(f.newSubfield('a', b.getTitle() + (b.getSubtitle() != null ? " :" : "")));
        if (b.getSubtitle() != null) title.addSubfield(f.newSubfield('b', b.getSubtitle()));
        r.addVariableField(title);
        if (b.getEdition() != null) r.addVariableField(df(f, "250", ' ', ' ', 'a', b.getEdition()));
        DataField pub = f.newDataField("264", ' ', '1');
        if (b.getPublisher() != null) pub.addSubfield(f.newSubfield('b', b.getPublisher()));
        if (b.getPublicationYear() != null) pub.addSubfield(f.newSubfield('c', String.valueOf(b.getPublicationYear())));
        if (!pub.getSubfields().isEmpty()) r.addVariableField(pub);
        if (b.getSummary() != null) r.addVariableField(df(f, "520", ' ', ' ', 'a', b.getSummary()));
        if (b.getSubjects() != null) {
            for (String s : b.getSubjects().split("\\s*;\\s*")) {
                if (!s.isBlank()) r.addVariableField(df(f, "650", ' ', '0', 'a', s.trim()));
            }
        }
        for (int i = 1; i < authors.size(); i++) r.addVariableField(df(f, "700", '1', ' ', 'a', authors.get(i)));
        for (Item it : items) {
            DataField h = f.newDataField("952", ' ', ' ');
            h.addSubfield(f.newSubfield('p', it.getBarcode()));
            if (it.getShelfLocation() != null) h.addSubfield(f.newSubfield('c', it.getShelfLocation()));
            if (b.getCallNumber() != null) h.addSubfield(f.newSubfield('o', b.getCallNumber()));
            h.addSubfield(f.newSubfield('y', b.getMaterialType().name()));
            r.addVariableField(h);
        }
        return r;
    }

    private static DataField df(MarcFactory f, String tag, char i1, char i2, char code, String value) {
        DataField d = f.newDataField(tag, i1, i2);
        d.addSubfield(f.newSubfield(code, value));
        return d;
    }

    private static String isbn(Record r) {
        for (VariableField v : r.getVariableFields("020")) {
            String raw = ((DataField) v).getSubfieldsAsString("a");
            if (raw == null) continue;
            Matcher m = ISBN.matcher(raw);
            if (m.find()) {
                String digits = m.group(1).replaceAll("[^0-9Xx]", "").toUpperCase();
                if (digits.length() == 10 || digits.length() == 13) return digits;
            }
        }
        return null;
    }

    private static String sub(Record r, String tag, char code) {
        DataField d = (DataField) r.getVariableField(tag);
        if (d == null) return null;
        Subfield s = d.getSubfield(code);
        return s == null ? null : s.getData();
    }

    private static String join(Record r, String tag, char... codes) {
        DataField d = (DataField) r.getVariableField(tag);
        return d == null ? null : join(d, codes);
    }

    private static String join(DataField d, char... codes) {
        List<String> parts = new ArrayList<>();
        for (char c : codes) {
            for (Subfield s : d.getSubfields(c)) {
                if (s.getData() != null && !s.getData().isBlank()) parts.add(s.getData().trim());
            }
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** Strips ISBD punctuation that MARC puts at the end of subfields (" /", " :", " ;", ","...). */
    static String clean(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("[\\s/:;,=]+$", "").replaceAll("\\.$", "").trim();
        if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1);
        return t.isEmpty() ? null : truncate(t, 500);
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }
}

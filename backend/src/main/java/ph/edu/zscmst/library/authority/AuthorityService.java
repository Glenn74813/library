package ph.edu.zscmst.library.authority;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.catalog.BibRecord;
import ph.edu.zscmst.library.catalog.BibRecordRepository;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;

/**
 * Authority control for author and subject headings: one preferred form per name/topic, variant
 * forms that redirect to it in searches, and catalog-wide rename/merge of headings.
 */
@Service
public class AuthorityService {

    public record HeadingRow(Long id, String heading, long usage, List<String> variants, String note) {
    }

    private final AuthorityRepository authorities;
    private final BibRecordRepository records;
    private final AuditService audit;

    public AuthorityService(AuthorityRepository authorities, BibRecordRepository records, AuditService audit) {
        this.authorities = authorities;
        this.records = records;
        this.audit = audit;
    }

    /** Every heading in use or on file, with usage counts. */
    @Transactional(readOnly = true)
    public List<HeadingRow> list(Authority.Type type, String q) {
        Map<String, HeadingRow> rows = new LinkedHashMap<>();
        for (Object[] u : authorities.usage(type.name())) {
            String h = (String) u[0];
            rows.put(h.toLowerCase(Locale.ROOT), new HeadingRow(null, h, ((Number) u[1]).longValue(), List.of(), null));
        }
        for (Authority a : authorities.findByTypeOrderByHeadingAsc(type)) {
            HeadingRow used = rows.get(a.getHeading().toLowerCase(Locale.ROOT));
            rows.put(a.getHeading().toLowerCase(Locale.ROOT), new HeadingRow(a.getId(), a.getHeading(),
                    used == null ? 0 : used.usage(), a.variantList(), a.getNote()));
        }
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return rows.values().stream()
                .filter(r -> needle.isEmpty() || r.heading().toLowerCase(Locale.ROOT).contains(needle)
                        || r.variants().stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(needle)))
                .sorted((a, b) -> a.heading().compareToIgnoreCase(b.heading()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> suggest(Authority.Type type, String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) return List.of();
        return list(type, needle).stream()
                .sorted((a, b) -> Long.compare(b.usage(), a.usage()))
                .map(HeadingRow::heading).limit(8).toList();
    }

    @Transactional
    public HeadingRow save(Authority.Type type, Long id, String heading, List<String> variants, String note) {
        String h = clean(heading);
        if (h == null) throw ApiException.badRequest("Heading is required");
        Authority a = id == null ? authorities.findFirstByTypeAndHeadingIgnoreCase(type, h).orElseGet(() -> new Authority(type, h))
                : authorities.findById(id).orElseThrow(() -> ApiException.notFound("Authority"));
        a.setHeading(h);
        a.setVariantList(variants == null ? List.of() : variants);
        a.setNote(clean(note));
        authorities.save(a);
        audit.record("AUTHORITY_SAVE", type + " " + h + (variants == null || variants.isEmpty() ? "" : " (variants: " + variants + ")"));
        return list(type, h).stream().filter(r -> r.heading().equalsIgnoreCase(h)).findFirst().orElseThrow();
    }

    @Transactional
    public void delete(Long id) {
        authorities.deleteById(id);
    }

    /**
     * Replaces heading {@code from} with {@code to} in every record (merging duplicates) and keeps
     * {@code from} as a variant of {@code to}, so searches for the old form still find the titles.
     */
    @Transactional
    public int rename(Authority.Type type, String from, String to) {
        String f = clean(from);
        String t = clean(to);
        if (f == null || t == null) throw ApiException.badRequest("Both headings are required");
        int changed = 0;
        for (BibRecord r : records.findAll()) {
            String field = type == Authority.Type.AUTHOR ? r.getAuthors() : r.getSubjects();
            if (field == null) continue;
            List<String> parts = new ArrayList<>(List.of(field.split("\\s*;\\s*")));
            if (parts.stream().noneMatch(p -> p.trim().equalsIgnoreCase(f))) continue;
            Set<String> merged = new LinkedHashSet<>();
            for (String p : parts) merged.add(p.trim().equalsIgnoreCase(f) ? t : p.trim());
            String joined = String.join("; ", merged);
            if (type == Authority.Type.AUTHOR) r.setAuthors(joined.length() > 500 ? joined.substring(0, 500) : joined);
            else r.setSubjects(joined.length() > 1000 ? joined.substring(0, 1000) : joined);
            changed++;
        }
        Authority target = authorities.findFirstByTypeAndHeadingIgnoreCase(type, t).orElseGet(() -> new Authority(type, t));
        List<String> variants = new ArrayList<>(target.variantList());
        variants.add(f);
        authorities.findFirstByTypeAndHeadingIgnoreCase(type, f).ifPresent(old -> {
            variants.addAll(old.variantList());
            if (!old.getHeading().equalsIgnoreCase(t)) authorities.delete(old);
        });
        target.setVariantList(variants);
        authorities.save(target);
        audit.record("AUTHORITY_MERGE", type + ": \"" + f + "\" -> \"" + t + "\" in " + changed + " record(s)");
        return changed;
    }

    /** Maps a searched form to its preferred heading if it is a known variant; otherwise returns it unchanged. */
    @Transactional(readOnly = true)
    public String preferred(Authority.Type type, String form) {
        if (form == null || form.isBlank()) return form;
        return authorities.findByVariant(type.name(), form.trim()).map(Authority::getHeading).orElse(form);
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim().replaceAll("\\s+", " ");
    }
}

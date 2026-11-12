package ph.edu.zscmst.library.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.common.ApiException;

/**
 * The library's sections / areas, their aisles and how many shelves each aisle has (Administration →
 * Sections &amp; Aisles). A copy's place and a stocktake's scope must come from here, in the list's spelling,
 * so a stocktake compares like with like.
 */
@Service
public class SectionService {

    public record Aisle(Long id, String name, int shelves, long copies) {
    }

    public record Section(Long id, String name, int sortOrder, long copies, List<Aisle> aisles) {
    }

    /** Where a copy is: section, aisle and shelf in the list's spelling (each may be null). */
    public record Spot(String section, String aisle, String shelf) {
    }

    private final JdbcTemplate jdbc;

    public SectionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Section> list() {
        Map<Long, List<Aisle>> aisles = new LinkedHashMap<>();
        jdbc.query("""
                select a.id, a.section_id, a.name, a.shelves,
                       (select count(*) from item i where lower(i.location) = lower(s.name)
                            and lower(trim(i.aisle)) = lower(a.name)) as copies
                from library_aisle a join library_section s on s.id = a.section_id
                order by a.sort_order, a.id""", rs -> {
            aisles.computeIfAbsent(rs.getLong("section_id"), k -> new ArrayList<>())
                    .add(new Aisle(rs.getLong("id"), rs.getString("name"), rs.getInt("shelves"), rs.getLong("copies")));
        });
        return jdbc.query("""
                select s.id, s.name, s.sort_order,
                       (select count(*) from item i where lower(i.location) = lower(s.name)) as copies
                from library_section s order by s.sort_order, lower(s.name)""",
                (rs, n) -> new Section(rs.getLong("id"), rs.getString("name"), rs.getInt("sort_order"), rs.getLong("copies"),
                        aisles.getOrDefault(rs.getLong("id"), List.of())));
    }

    public Optional<String> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return jdbc.queryForList("select name from library_section where lower(name) = lower(?)", String.class, name.trim())
                .stream().findFirst();
    }

    /** The list's spelling of {@code name}; blank means "not set"; anything else not on the list is refused. */
    public String require(String name) {
        if (name == null || name.isBlank()) return null;
        return find(name).orElseThrow(() -> ApiException.badRequest("\"" + name.trim()
                + "\" is not one of the library's sections. Pick one from the list (an administrator sets them up under Sections & Aisles)."));
    }

    /** An aisle of a section: its spelling and shelf count. */
    private Optional<Map<String, Object>> aisle(String section, String aisle) {
        return jdbc.queryForList("""
                select a.name, a.shelves from library_aisle a join library_section s on s.id = a.section_id
                where lower(s.name) = lower(?) and lower(a.name) = lower(?)""", section, aisle.trim()).stream().findFirst();
    }

    /** The aisle's spelling when it is one of the section's aisles; otherwise refused. */
    public String requireAisle(String section, String aisle) {
        if (aisle == null || aisle.isBlank()) return null;
        if (section == null) throw ApiException.badRequest("Choose the section first, then its aisle");
        return aisle(section, aisle).map(a -> (String) a.get("name")).orElseThrow(() -> ApiException.badRequest(
                "Aisle " + aisle.trim() + " is not one of the aisles of " + section + "."));
    }

    /** Checks a copy's section, aisle and shelf against the layout and returns them in the list's spelling. */
    public Spot requireSpot(String section, String aisle, String shelf) {
        String s = require(section);
        String a = requireAisle(s, aisle);
        if (shelf == null || shelf.isBlank()) return new Spot(s, a, null);
        if (a == null) throw ApiException.badRequest("Choose the aisle first, then its shelf");
        int shelves = ((Number) aisle(s, a).orElseThrow().get("shelves")).intValue();
        String t = shelf.trim();
        if (!t.matches("\\d{1,2}") || Integer.parseInt(t) < 1 || Integer.parseInt(t) > shelves) {
            throw ApiException.badRequest("Aisle " + a + " of " + s + (shelves == 1 ? " has only shelf 1." : " has shelves 1 to " + shelves + "."));
        }
        return new Spot(s, a, String.valueOf(Integer.parseInt(t)));
    }

    /**
     * For imports: the list's spelling, adding a new section or aisle (and raising an aisle's shelf count)
     * so imported copies keep the place recorded in the file.
     */
    @Transactional
    public Spot findOrAddSpot(String section, String aisle, String shelf) {
        if (section == null || section.isBlank()) return new Spot(null, null, null);
        String clean = section.trim().length() > 100 ? section.trim().substring(0, 100) : section.trim();
        jdbc.update("""
                insert into library_section (name, sort_order)
                select ?, coalesce(max(sort_order), 0) + 1 from library_section
                on conflict do nothing""", clean);
        String s = find(clean).orElse(clean);
        if (aisle == null || aisle.isBlank()) return new Spot(s, null, null);
        String a = aisle.trim().length() > 20 ? aisle.trim().substring(0, 20) : aisle.trim();
        Integer shelfNo = shelf != null && shelf.trim().matches("\\d{1,2}") ? Integer.valueOf(shelf.trim()) : null;
        int need = Math.min(50, Math.max(1, shelfNo == null ? 1 : shelfNo));
        jdbc.update("""
                insert into library_aisle (section_id, name, shelves, sort_order)
                select s.id, ?, ?, coalesce((select max(sort_order) from library_aisle where section_id = s.id), 0) + 1
                from library_section s where lower(s.name) = lower(?)
                on conflict do nothing""", a, need, s);
        jdbc.update("""
                update library_aisle a set shelves = greatest(a.shelves, ?) from library_section s
                where s.id = a.section_id and lower(s.name) = lower(?) and lower(a.name) = lower(?)""", need, s, a);
        String spelled = aisle(s, a).map(x -> (String) x.get("name")).orElse(a);
        return new Spot(s, spelled, shelfNo == null ? null : String.valueOf(shelfNo));
    }
}

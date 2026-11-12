package ph.edu.zscmst.library.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.common.ApiException;

/**
 * The library's sections / areas. A copy's section and a stocktake's location must be one of them, in the
 * list's spelling, so a stocktake's "misplaced" check compares like with like.
 */
@Service
public class SectionService {

    public record Section(Long id, String name, int sortOrder, long copies) {
    }

    private final JdbcTemplate jdbc;

    public SectionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Section> list() {
        return jdbc.query("""
                select s.id, s.name, s.sort_order,
                       (select count(*) from item i where lower(i.location) = lower(s.name)) as copies
                from library_section s order by s.sort_order, lower(s.name)""",
                (rs, n) -> new Section(rs.getLong("id"), rs.getString("name"), rs.getInt("sort_order"), rs.getLong("copies")));
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
                + "\" is not one of the library's sections. Pick one from the list, or add it under Collection → Sections."));
    }

    /** For imports: the list's spelling, adding the section to the list when it is new. */
    @Transactional
    public String findOrAdd(String name) {
        if (name == null || name.isBlank()) return null;
        String clean = name.trim().length() > 100 ? name.trim().substring(0, 100) : name.trim();
        jdbc.update("""
                insert into library_section (name, sort_order)
                select ?, coalesce(max(sort_order), 0) + 1 from library_section
                on conflict do nothing""", clean);
        return find(clean).orElse(clean);
    }
}

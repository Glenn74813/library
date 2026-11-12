package ph.edu.zscmst.library.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;

/**
 * Administration → Sections &amp; Aisles: the sections, their aisles and shelves that copies and stocktakes pick
 * from. Everyone on staff can read the layout; only administrators change it.
 */
@RestController
public class SectionController {

    public record SectionRequest(@NotBlank @Size(max = 100) String name, Integer sortOrder) {
    }

    /** One aisle (name given) or {@code count} aisles numbered after the library's highest aisle number. */
    public record AisleRequest(@Size(max = 20) String name, @Min(1) @Max(30) Integer count,
                               @NotNull @Min(1) @Max(50) Integer shelves) {
    }

    public record AisleUpdate(@NotBlank @Size(max = 20) String name, @NotNull @Min(1) @Max(50) Integer shelves) {
    }

    private final SectionService sections;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SectionController(SectionService sections, JdbcTemplate jdbc, AuditService audit) {
        this.sections = sections;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/api/sections")
    public List<SectionService.Section> list() {
        return sections.list();
    }

    @PostMapping("/api/sections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<SectionService.Section> create(@Valid @RequestBody SectionRequest req) {
        String name = req.name().trim();
        if (sections.find(name).isPresent()) throw ApiException.conflict("There is already a section called " + name);
        jdbc.update("insert into library_section (name, sort_order) select ?, coalesce(max(sort_order), 0) + 1 from library_section",
                name);
        audit.record("SECTION_ADD", name);
        return sections.list();
    }

    /**
     * Renames a section (or changes its place in the list). Its copies and open stocktakes follow the new
     * name; renaming to another section's name merges the two.
     */
    @PutMapping("/api/sections/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<SectionService.Section> update(@PathVariable Long id, @Valid @RequestBody SectionRequest req) {
        String old = jdbc.queryForList("select name from library_section where id = ? for update", String.class, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Section"));
        String name = req.name().trim();
        String target = sections.find(name).filter(n -> !n.equalsIgnoreCase(old)).orElse(null);
        if (target != null) {
            // Merge into the existing section, aisles too: same-named aisles become one (keeping the larger shelf count).
            Long targetId = jdbc.queryForObject("select id from library_section where lower(name) = lower(?)", Long.class, target);
            jdbc.update("""
                    update library_aisle t set shelves = greatest(t.shelves, a.shelves) from library_aisle a
                    where a.section_id = ? and t.section_id = ? and lower(t.name) = lower(a.name)""", id, targetId);
            jdbc.update("""
                    delete from library_aisle a using library_aisle t
                    where a.section_id = ? and t.section_id = ? and lower(t.name) = lower(a.name)""", id, targetId);
            jdbc.update("update library_aisle set section_id = ? where section_id = ?", targetId, id);
            move(old, target);
            jdbc.update("delete from library_section where id = ?", id);
            audit.record("SECTION_MERGE", old + " -> " + target);
            return sections.list();
        }
        try {
            jdbc.update("update library_section set name = ?, sort_order = coalesce(?, sort_order) where id = ?",
                    name, req.sortOrder(), id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("There is already a section called " + name);
        }
        if (!old.equals(name)) {
            move(old, name);
            audit.record("SECTION_RENAME", old + " -> " + name);
        }
        return sections.list();
    }

    @DeleteMapping("/api/sections/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<SectionService.Section> delete(@PathVariable Long id) {
        String name = jdbc.queryForList("select name from library_section where id = ? for update", String.class, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Section"));
        Long copies = jdbc.queryForObject("select count(*) from item where lower(location) = lower(?)", Long.class, name);
        if (copies != null && copies > 0) {
            throw ApiException.conflict(copies + " cop" + (copies == 1 ? "y is" : "ies are")
                    + " in " + name + ". Move them first, or rename this section to another one to merge them.");
        }
        jdbc.update("delete from library_section where id = ?", id);
        audit.record("SECTION_DELETE", name);
        return sections.list();
    }

    // ------------------------------------------------------------------ aisles

    @PostMapping("/api/sections/{id}/aisles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<SectionService.Section> addAisles(@PathVariable Long id, @Valid @RequestBody AisleRequest req) {
        String section = sectionName(id);
        List<String> names = new ArrayList<>();
        if (req.name() != null && !req.name().isBlank()) {
            names.add(req.name().trim());
        } else {
            // Aisle numbers run through the whole library, so "Aisle 7" is one place.
            Integer max = jdbc.queryForObject(
                    "select coalesce(max(name::int), 0) from library_aisle where name ~ '^[0-9]{1,6}$'", Integer.class);
            int next = (max == null ? 0 : max) + 1;
            for (int i = 0; i < (req.count() == null ? 1 : req.count()); i++) names.add(String.valueOf(next + i));
        }
        for (String name : names) {
            if (!jdbc.queryForList("select 1 from library_aisle where section_id = ? and lower(name) = lower(?)", id, name).isEmpty()) {
                throw ApiException.conflict(section + " already has an aisle " + name);
            }
            jdbc.update("""
                    insert into library_aisle (section_id, name, shelves, sort_order)
                    values (?, ?, ?, (select coalesce(max(sort_order), 0) + 1 from library_aisle where section_id = ?))""",
                    id, name, req.shelves(), id);
        }
        audit.record("AISLE_ADD", section + ": aisle " + String.join(", ", names) + " (" + req.shelves() + (req.shelves() == 1 ? " shelf)" : " shelves)"));
        return sections.list();
    }

    /** Renames an aisle (its copies follow) or changes its number of shelves. */
    @PutMapping("/api/aisles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<SectionService.Section> updateAisle(@PathVariable Long id, @Valid @RequestBody AisleUpdate req) {
        Map<String, Object> a = aisleRow(id);
        String section = (String) a.get("section");
        String old = (String) a.get("name");
        String name = req.name().trim();
        if (!old.equalsIgnoreCase(name) && !jdbc.queryForList(
                "select 1 from library_aisle where section_id = ? and lower(name) = lower(?)", a.get("section_id"), name).isEmpty()) {
            throw ApiException.conflict(section + " already has an aisle " + name);
        }
        Long above = jdbc.queryForObject("""
                select count(*) from item where lower(location) = lower(?) and lower(trim(aisle)) = lower(?)
                and trim(shelf) ~ '^[0-9]{1,2}$' and trim(shelf)::int > ?""", Long.class, section, old, req.shelves());
        if (above != null && above > 0) {
            throw ApiException.conflict(above + " cop" + (above == 1 ? "y is" : "ies are") + " on shelves above " + req.shelves()
                    + " in aisle " + old + ". Move them first.");
        }
        jdbc.update("update library_aisle set name = ?, shelves = ? where id = ?", name, req.shelves(), id);
        if (!old.equals(name)) {
            jdbc.update("update item set aisle = ? where lower(location) = lower(?) and lower(trim(aisle)) = lower(?)", name, section, old);
            jdbc.update("""
                    update stocktake_session set aisle = ? where closed_at is null and lower(location) = lower(?)
                    and lower(aisle) = lower(?)""", name, section, old);
        }
        audit.record("AISLE_UPDATE", section + ": aisle " + old + " -> " + name + ", " + req.shelves() + (req.shelves() == 1 ? " shelf" : " shelves"));
        return sections.list();
    }

    @DeleteMapping("/api/aisles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<SectionService.Section> deleteAisle(@PathVariable Long id) {
        Map<String, Object> a = aisleRow(id);
        String section = (String) a.get("section");
        String name = (String) a.get("name");
        Long copies = jdbc.queryForObject("select count(*) from item where lower(location) = lower(?) and lower(trim(aisle)) = lower(?)",
                Long.class, section, name);
        if (copies != null && copies > 0) {
            throw ApiException.conflict(copies + " cop" + (copies == 1 ? "y is" : "ies are") + " in aisle " + name + ". Move them first.");
        }
        jdbc.update("delete from library_aisle where id = ?", id);
        audit.record("AISLE_DELETE", section + ": aisle " + name);
        return sections.list();
    }

    private String sectionName(Long id) {
        return jdbc.queryForList("select name from library_section where id = ? for update", String.class, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Section"));
    }

    private Map<String, Object> aisleRow(Long id) {
        return jdbc.queryForList("""
                select a.id, a.section_id, a.name, s.name as section from library_aisle a
                join library_section s on s.id = a.section_id where a.id = ? for update of a""", id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Aisle"));
    }

    private void move(String from, String to) {
        jdbc.update("update item set location = ? where lower(location) = lower(?)", to, from);
        jdbc.update("update stocktake_session set location = ? where closed_at is null and lower(location) = lower(?)", to, from);
    }
}

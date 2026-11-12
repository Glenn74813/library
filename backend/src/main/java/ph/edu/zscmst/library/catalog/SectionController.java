package ph.edu.zscmst.library.catalog;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** Collection → Sections: the list copies and stocktakes pick their section / area from. */
@RestController
public class SectionController {

    public record SectionRequest(@NotBlank @Size(max = 100) String name, Integer sortOrder) {
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
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
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
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public List<SectionService.Section> update(@PathVariable Long id, @Valid @RequestBody SectionRequest req) {
        String old = jdbc.queryForList("select name from library_section where id = ? for update", String.class, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Section"));
        String name = req.name().trim();
        String target = sections.find(name).filter(n -> !n.equalsIgnoreCase(old)).orElse(null);
        if (target != null) {
            // Merge into the existing section.
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
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
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

    private void move(String from, String to) {
        jdbc.update("update item set location = ? where lower(location) = lower(?)", to, from);
        jdbc.update("update stocktake_session set location = ? where closed_at is null and lower(location) = lower(?)", to, from);
    }
}

package ph.edu.zscmst.library.catalog;

import java.util.List;
import java.util.Locale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
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

@RestController
public class CustomFieldController {

    public record FieldDto(Long id, String key, String label, CustomField.Type type, List<String> options,
                           boolean showInOpac, int sortOrder) {
        static FieldDto of(CustomField f) {
            return new FieldDto(f.getId(), f.getKey(), f.getLabel(), f.getType(), f.optionList(), f.isShowInOpac(), f.getSortOrder());
        }
    }

    public record FieldRequest(@NotBlank @Size(max = 80) String label, @NotNull CustomField.Type type,
                               List<@Size(max = 100) String> options, boolean showInOpac, int sortOrder) {
    }

    private final CustomFieldRepository fields;
    private final AuditService audit;

    public CustomFieldController(CustomFieldRepository fields, AuditService audit) {
        this.fields = fields;
        this.audit = audit;
    }

    @GetMapping("/api/custom-fields")
    public List<FieldDto> list() {
        return fields.findAllByOrderBySortOrderAscLabelAsc().stream().map(FieldDto::of).toList();
    }

    @PostMapping("/api/custom-fields")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public FieldDto create(@Valid @RequestBody FieldRequest req) {
        String base = req.label().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        String key = base.isEmpty() ? "field" : base.substring(0, Math.min(30, base.length()));
        String stem = key;
        for (int i = 2; fields.existsByKey(key); i++) key = stem + "_" + i;
        CustomField f = new CustomField(key);
        apply(f, req);
        audit.record("CUSTOM_FIELD", "created " + f.getLabel());
        return FieldDto.of(fields.save(f));
    }

    @PutMapping("/api/custom-fields/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public FieldDto update(@PathVariable Long id, @Valid @RequestBody FieldRequest req) {
        CustomField f = fields.findById(id).orElseThrow(() -> ApiException.notFound("Field"));
        apply(f, req);
        return FieldDto.of(f);
    }

    /** Removing a field hides it; values already stored on records are kept (and reappear if re-created). */
    @DeleteMapping("/api/custom-fields/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public void delete(@PathVariable Long id) {
        fields.deleteById(id);
    }

    private static void apply(CustomField f, FieldRequest req) {
        f.setLabel(req.label().trim());
        f.setType(req.type());
        List<String> opts = req.options() == null ? List.of() : req.options().stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (req.type() == CustomField.Type.SELECT && opts.isEmpty()) throw ApiException.badRequest("A choice list needs options");
        f.setOptions(opts.isEmpty() ? null : String.join("|", opts));
        f.setShowInOpac(req.showInOpac());
        f.setSortOrder(req.sortOrder());
    }
}

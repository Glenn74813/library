package ph.edu.zscmst.library.patron;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;

/** Patron categories (loan rules) and courses/programs. */
@RestController
@RequestMapping("/api")
public class ReferenceDataController {

    public record CategoryDto(Long id, String name, int loanDays, int maxLoans, int maxRenewals,
                              BigDecimal finePerDay, BigDecimal maxUnpaidFines, int holdPickupDays) {
        static CategoryDto of(PatronCategory c) {
            return new CategoryDto(c.getId(), c.getName(), c.getLoanDays(), c.getMaxLoans(), c.getMaxRenewals(),
                    c.getFinePerDay(), c.getMaxUnpaidFines(), c.getHoldPickupDays());
        }
    }

    public record CategoryRequest(@NotBlank @Size(max = 60) String name, @Min(1) int loanDays, @Min(0) int maxLoans,
                                  @Min(0) int maxRenewals, @NotNull @DecimalMin("0") BigDecimal finePerDay,
                                  @NotNull @DecimalMin("0") BigDecimal maxUnpaidFines,
                                  @Min(1) @Max(30) int holdPickupDays) {
    }

    public record CourseDto(Long id, String code, String name) {
        static CourseDto of(Course c) {
            return new CourseDto(c.getId(), c.getCode(), c.getName());
        }
    }

    public record CourseRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 150) String name) {
    }

    private final PatronCategoryRepository categories;
    private final CourseRepository courses;
    private final PatronRepository patrons;
    private final AuditService audit;

    public ReferenceDataController(PatronCategoryRepository categories, CourseRepository courses,
                                   PatronRepository patrons, AuditService audit) {
        this.categories = categories;
        this.courses = courses;
        this.patrons = patrons;
        this.audit = audit;
    }

    @GetMapping("/patron-categories")
    public List<CategoryDto> listCategories() {
        return categories.findAllByOrderByNameAsc().stream().map(CategoryDto::of).toList();
    }

    @PostMapping("/patron-categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public CategoryDto createCategory(@Valid @RequestBody CategoryRequest req) {
        PatronCategory c = new PatronCategory(req.name().trim());
        apply(c, req);
        audit.record("CATEGORY_CREATE", c.getName());
        return CategoryDto.of(categories.save(c));
    }

    @PutMapping("/patron-categories/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public CategoryDto updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) {
        PatronCategory c = categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
        c.setName(req.name().trim());
        apply(c, req);
        audit.record("CATEGORY_UPDATE", c.getName());
        return CategoryDto.of(c);
    }

    @DeleteMapping("/patron-categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public void deleteCategory(@PathVariable Long id) {
        if (patrons.existsByCategoryId(id)) {
            throw ApiException.conflict("Category is assigned to patrons");
        }
        categories.deleteById(id);
    }

    private static void apply(PatronCategory c, CategoryRequest req) {
        c.setLoanDays(req.loanDays());
        c.setMaxLoans(req.maxLoans());
        c.setMaxRenewals(req.maxRenewals());
        c.setFinePerDay(req.finePerDay());
        c.setMaxUnpaidFines(req.maxUnpaidFines());
        c.setHoldPickupDays(req.holdPickupDays());
    }

    @GetMapping("/courses")
    public List<CourseDto> listCourses() {
        return courses.findAllByOrderByCodeAsc().stream().map(CourseDto::of).toList();
    }

    @PostMapping("/courses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public CourseDto createCourse(@Valid @RequestBody CourseRequest req) {
        return CourseDto.of(courses.save(new Course(req.code().trim().toUpperCase(), req.name().trim())));
    }

    @PutMapping("/courses/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public CourseDto updateCourse(@PathVariable Long id, @Valid @RequestBody CourseRequest req) {
        Course c = courses.findById(id).orElseThrow(() -> ApiException.notFound("Course"));
        c.setCode(req.code().trim().toUpperCase());
        c.setName(req.name().trim());
        return CourseDto.of(c);
    }

    @DeleteMapping("/courses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    @Transactional
    public void deleteCourse(@PathVariable Long id) {
        if (patrons.existsByCourseId(id)) {
            throw ApiException.conflict("Course is assigned to patrons");
        }
        courses.deleteById(id);
    }
}

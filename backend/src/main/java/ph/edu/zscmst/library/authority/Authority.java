package ph.edu.zscmst.library.authority;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A preferred heading (author or subject) and the variant forms that should lead to it. */
@Entity
@Table(name = "authority")
public class Authority {

    public enum Type { AUTHOR, SUBJECT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    private Type type;
    private String heading;
    private String variants;
    private String note;
    private Instant createdAt = Instant.now();

    protected Authority() {
    }

    public Authority(Type type, String heading) {
        this.type = type;
        this.heading = heading;
    }

    public List<String> variantList() {
        return variants == null || variants.isBlank() ? List.of()
                : Arrays.stream(variants.split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public void setVariantList(List<String> list) {
        String joined = String.join("|", list.stream().map(String::trim).filter(s -> !s.isEmpty() && !s.equalsIgnoreCase(heading))
                .distinct().toList());
        this.variants = joined.isEmpty() ? null : (joined.length() > 1000 ? joined.substring(0, 1000) : joined);
    }

    public Long getId() { return id; }
    public Type getType() { return type; }
    public String getHeading() { return heading; }
    public void setHeading(String heading) { this.heading = heading; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}

package ph.edu.zscmst.library.digital;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One opening of a school-owned e-book (for usage statistics). */
@Entity
@Table(name = "ebook_view")
public class EbookView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long recordId;
    private Long patronId;
    private Instant viewedAt = Instant.now();

    protected EbookView() {
    }

    public EbookView(Long recordId, Long patronId) {
        this.recordId = recordId;
        this.patronId = patronId;
    }

    public Long getId() { return id; }
    public Long getRecordId() { return recordId; }
    public Long getPatronId() { return patronId; }
    public Instant getViewedAt() { return viewedAt; }
}

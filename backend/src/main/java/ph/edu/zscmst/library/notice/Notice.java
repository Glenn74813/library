package ph.edu.zscmst.library.notice;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import ph.edu.zscmst.library.patron.Patron;

/** An automated patron notice. {@code reference} de-duplicates (one notice per loan/hold event). */
@Entity
@Table(name = "notice")
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patron_id")
    private Patron patron;
    @Enumerated(EnumType.STRING)
    private NoticeType type;
    private String message;
    private String reference;
    private Instant createdAt = Instant.now();
    private Instant sentAt;

    protected Notice() {
    }

    public Notice(Patron patron, NoticeType type, String message, String reference) {
        this.patron = patron;
        this.type = type;
        this.message = message.length() > 1000 ? message.substring(0, 1000) : message;
        this.reference = reference;
    }

    public Long getId() { return id; }
    public Patron getPatron() { return patron; }
    public NoticeType getType() { return type; }
    public String getMessage() { return message; }
    public String getReference() { return reference; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}

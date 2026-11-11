package ph.edu.zscmst.library.circulation;

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

/** A patron's request to renew a loan, decided by staff. */
@Entity
@Table(name = "renewal_request")
public class RenewalRequest {

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    public enum Channel { PORTAL, KIOSK, SIP2 }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id")
    private Loan loan;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patron_id")
    private Patron patron;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    private Instant requestedAt = Instant.now();
    private Instant dueAt;
    private Instant newDueAt;
    private Instant decidedAt;
    private String decidedBy;
    private String reason;

    protected RenewalRequest() {
    }

    public RenewalRequest(Loan loan, Channel channel) {
        this.loan = loan;
        this.patron = loan.getPatron();
        this.channel = channel;
        this.dueAt = loan.getDueAt();
    }

    public void decide(Status status, String by, String reason, Instant newDueAt) {
        this.status = status;
        this.decidedBy = by;
        this.reason = reason;
        this.newDueAt = newDueAt;
        this.decidedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Loan getLoan() { return loan; }
    public Patron getPatron() { return patron; }
    public Status getStatus() { return status; }
    public Channel getChannel() { return channel; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getDueAt() { return dueAt; }
    public Instant getNewDueAt() { return newDueAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecidedBy() { return decidedBy; }
    public String getReason() { return reason; }
}

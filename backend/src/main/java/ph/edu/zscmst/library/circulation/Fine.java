package ph.edu.zscmst.library.circulation;

import java.math.BigDecimal;
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

@Entity
@Table(name = "fine")
public class Fine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patron_id")
    private Patron patron;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_id")
    private Loan loan;
    private BigDecimal amount;
    private String reason;
    @Enumerated(EnumType.STRING)
    private FineStatus status = FineStatus.UNPAID;
    private Instant createdAt = Instant.now();
    private Instant settledAt;
    private String receiptNumber;
    private String settledBy;

    protected Fine() {
    }

    public Fine(Patron patron, Loan loan, BigDecimal amount, String reason) {
        this.patron = patron;
        this.loan = loan;
        this.amount = amount;
        this.reason = reason.length() > 255 ? reason.substring(0, 255) : reason;
    }

    public void settle(FineStatus status, String receiptNumber, String by, Instant at) {
        this.status = status;
        this.receiptNumber = receiptNumber;
        this.settledBy = by;
        this.settledAt = at;
    }

    public Long getId() { return id; }
    public Patron getPatron() { return patron; }
    public Loan getLoan() { return loan; }
    public BigDecimal getAmount() { return amount; }
    public String getReason() { return reason; }
    public FineStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSettledAt() { return settledAt; }
    public String getReceiptNumber() { return receiptNumber; }
    public String getSettledBy() { return settledBy; }
}

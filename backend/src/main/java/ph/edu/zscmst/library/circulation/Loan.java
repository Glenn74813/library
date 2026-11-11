package ph.edu.zscmst.library.circulation;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.patron.Patron;

@Entity
@Table(name = "loan")
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id")
    private Item item;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patron_id")
    private Patron patron;
    private Instant checkoutAt;
    private Instant dueAt;
    private Instant returnedAt;
    private int renewals;
    private String checkedOutBy;

    protected Loan() {
    }

    public Loan(Item item, Patron patron, Instant checkoutAt, Instant dueAt, String checkedOutBy) {
        this.item = item;
        this.patron = patron;
        this.checkoutAt = checkoutAt;
        this.dueAt = dueAt;
        this.checkedOutBy = checkedOutBy;
    }

    public boolean isActive() {
        return returnedAt == null;
    }

    public Long getId() { return id; }
    public Item getItem() { return item; }
    public Patron getPatron() { return patron; }
    public Instant getCheckoutAt() { return checkoutAt; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public Instant getReturnedAt() { return returnedAt; }
    public void setReturnedAt(Instant returnedAt) { this.returnedAt = returnedAt; }
    public int getRenewals() { return renewals; }
    public void setRenewals(int renewals) { this.renewals = renewals; }
    public String getCheckedOutBy() { return checkedOutBy; }
}

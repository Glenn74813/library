package ph.edu.zscmst.library.patron;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Borrowing rules per type of patron (loan period, limits, fines). */
@Entity
@Table(name = "patron_category")
public class PatronCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private int loanDays;
    private int maxLoans;
    private int maxRenewals;
    private BigDecimal finePerDay;
    private BigDecimal maxUnpaidFines;
    /** Days a reserved copy waits on the hold shelf before the reservation expires. */
    private int holdPickupDays = 3;
    private int maxHolds;
    private int fineGraceDays;
    private BigDecimal maxFinePerItem = BigDecimal.ZERO;

    protected PatronCategory() {
    }

    public PatronCategory(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getLoanDays() { return loanDays; }
    public void setLoanDays(int loanDays) { this.loanDays = loanDays; }
    public int getMaxLoans() { return maxLoans; }
    public void setMaxLoans(int maxLoans) { this.maxLoans = maxLoans; }
    public int getMaxRenewals() { return maxRenewals; }
    public void setMaxRenewals(int maxRenewals) { this.maxRenewals = maxRenewals; }
    public BigDecimal getFinePerDay() { return finePerDay; }
    public void setFinePerDay(BigDecimal finePerDay) { this.finePerDay = finePerDay; }
    public int getHoldPickupDays() { return holdPickupDays; }
    public void setHoldPickupDays(int holdPickupDays) { this.holdPickupDays = holdPickupDays; }
    public BigDecimal getMaxUnpaidFines() { return maxUnpaidFines; }
    public int getMaxHolds() { return maxHolds; }
    public void setMaxHolds(int maxHolds) { this.maxHolds = maxHolds; }
    public int getFineGraceDays() { return fineGraceDays; }
    public void setFineGraceDays(int fineGraceDays) { this.fineGraceDays = fineGraceDays; }
    public BigDecimal getMaxFinePerItem() { return maxFinePerItem; }
    public void setMaxFinePerItem(BigDecimal maxFinePerItem) { this.maxFinePerItem = maxFinePerItem; }
    public void setMaxUnpaidFines(BigDecimal maxUnpaidFines) { this.maxUnpaidFines = maxUnpaidFines; }
}

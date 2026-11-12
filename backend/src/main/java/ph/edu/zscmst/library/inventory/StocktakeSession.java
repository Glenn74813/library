package ph.edu.zscmst.library.inventory;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "stocktake_session")
public class StocktakeSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    /** Shelf location being counted; null means the whole collection. */
    private String location;
    /** Aisle of that section being counted; null means the whole section. */
    private String aisle;
    private String startedBy;
    private Instant startedAt = Instant.now();
    private Instant closedAt;

    protected StocktakeSession() {
    }

    public StocktakeSession(String name, String location, String aisle, String startedBy) {
        this.name = name;
        this.location = location;
        this.aisle = aisle;
        this.startedBy = startedBy;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getLocation() { return location; }
    public String getAisle() { return aisle; }
    public String getStartedBy() { return startedBy; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}

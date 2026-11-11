package ph.edu.zscmst.library.inventory;

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

@Entity
@Table(name = "stocktake_scan")
public class StocktakeScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private StocktakeSession session;
    private String code;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;
    private Instant scannedAt = Instant.now();

    protected StocktakeScan() {
    }

    public StocktakeScan(StocktakeSession session, String code, Item item) {
        this.session = session;
        this.code = code;
        this.item = item;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public Item getItem() { return item; }
    public Instant getScannedAt() { return scannedAt; }
}

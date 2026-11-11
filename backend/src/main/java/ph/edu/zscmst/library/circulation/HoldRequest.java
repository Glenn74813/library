package ph.edu.zscmst.library.circulation;

import java.time.Instant;
import java.time.LocalDate;

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

import ph.edu.zscmst.library.catalog.BibRecord;
import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.patron.Patron;

/** A reservation on a title; becomes READY when a specific copy is set aside. */
@Entity
@Table(name = "hold_request")
public class HoldRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "record_id")
    private BibRecord record;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patron_id")
    private Patron patron;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;
    @Enumerated(EnumType.STRING)
    private HoldStatus status = HoldStatus.PENDING;
    private Instant placedAt = Instant.now();
    private Instant readyAt;
    private Instant expiresAt;
    /** Requested pickup date for an advance booking; null for "next available copy". */
    private LocalDate pickupDate;

    protected HoldRequest() {
    }

    public HoldRequest(BibRecord record, Patron patron) {
        this.record = record;
        this.patron = patron;
    }

    public static HoldRequest booking(BibRecord record, Patron patron, LocalDate pickupDate) {
        HoldRequest h = new HoldRequest(record, patron);
        h.status = HoldStatus.SCHEDULED;
        h.pickupDate = pickupDate;
        return h;
    }

    public void markReady(Item item, Instant now, Instant expiresAt) {
        this.item = item;
        this.status = HoldStatus.READY;
        this.readyAt = now;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public BibRecord getRecord() { return record; }
    public Patron getPatron() { return patron; }
    public Item getItem() { return item; }
    public HoldStatus getStatus() { return status; }
    public void setStatus(HoldStatus status) { this.status = status; }
    public Instant getPlacedAt() { return placedAt; }
    public Instant getReadyAt() { return readyAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public LocalDate getPickupDate() { return pickupDate; }
}

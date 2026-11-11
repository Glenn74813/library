package ph.edu.zscmst.library.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

/** A physical copy, identified by barcode and (optionally) a UHF RFID tag EPC. */
@Entity
@Table(name = "item")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "record_id")
    private BibRecord record;
    private String barcode;
    private String rfidEpc;
    private String location;
    private String aisle;
    private String shelf;
    /** Null = follow the material-type rule; true/false = override for this copy. */
    private Boolean roomUseOnly;
    @Enumerated(EnumType.STRING)
    private ItemStatus status = ItemStatus.AVAILABLE;
    private LocalDate acquiredDate;
    private BigDecimal price;
    private String notes;
    private Instant createdAt = Instant.now();

    protected Item() {
    }

    public Item(BibRecord record, String barcode) {
        this.record = record;
        this.barcode = barcode;
    }

    public Long getId() { return id; }
    public BibRecord getRecord() { return record; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
    public String getRfidEpc() { return rfidEpc; }
    public void setRfidEpc(String rfidEpc) { this.rfidEpc = rfidEpc; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Boolean getRoomUseOnly() { return roomUseOnly; }
    public void setRoomUseOnly(Boolean roomUseOnly) { this.roomUseOnly = roomUseOnly; }
    public String getAisle() { return aisle; }
    public void setAisle(String aisle) { this.aisle = aisle; }
    public String getShelf() { return shelf; }
    public void setShelf(String shelf) { this.shelf = shelf; }

    /** Human-readable place on the floor, e.g. "Fiction Section · Aisle 3 · Shelf 2". */
    public String getShelfLocation() {
        List<String> parts = new ArrayList<>();
        if (location != null) parts.add(location);
        if (aisle != null) parts.add("Aisle " + aisle);
        if (shelf != null) parts.add("Shelf " + shelf);
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    public ItemStatus getStatus() { return status; }
    public void setStatus(ItemStatus status) { this.status = status; }
    public LocalDate getAcquiredDate() { return acquiredDate; }
    public void setAcquiredDate(LocalDate acquiredDate) { this.acquiredDate = acquiredDate; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
}

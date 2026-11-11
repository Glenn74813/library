package ph.edu.zscmst.library.gate;

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

import ph.edu.zscmst.library.catalog.Item;

/** Timestamped security gate event log (alarms, tamper, power, visitor counts). */
@Entity
@Table(name = "gate_event")
public class GateEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String gateId;
    @Enumerated(EnumType.STRING)
    private GateEventType eventType;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;
    private String epc;
    private Integer visitorCount;
    private String message;
    private Instant occurredAt;

    protected GateEvent() {
    }

    public GateEvent(String gateId, GateEventType eventType, Instant occurredAt) {
        this.gateId = gateId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public String getGateId() { return gateId; }
    public GateEventType getEventType() { return eventType; }
    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }
    public String getEpc() { return epc; }
    public void setEpc(String epc) { this.epc = epc; }
    public Integer getVisitorCount() { return visitorCount; }
    public void setVisitorCount(Integer visitorCount) { this.visitorCount = visitorCount; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getOccurredAt() { return occurredAt; }
}

package ph.edu.zscmst.library.attendance;

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
@Table(name = "attendance_log")
public class AttendanceLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patron_id")
    private Patron patron;
    private Instant timeIn;
    private Instant timeOut;
    @Enumerated(EnumType.STRING)
    private EntryMethod method;

    protected AttendanceLog() {
    }

    public AttendanceLog(Patron patron, Instant timeIn, EntryMethod method) {
        this.patron = patron;
        this.timeIn = timeIn;
        this.method = method;
    }

    public Long getId() { return id; }
    public Patron getPatron() { return patron; }
    public Instant getTimeIn() { return timeIn; }
    public Instant getTimeOut() { return timeOut; }
    public void setTimeOut(Instant timeOut) { this.timeOut = timeOut; }
    public EntryMethod getMethod() { return method; }
}

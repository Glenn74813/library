package ph.edu.zscmst.library.calendar;

import java.time.LocalTime;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Opening hours for one weekday (1 = Monday). Null times mean the library is closed that day. */
@Entity
@Table(name = "library_hours")
public class LibraryHours {

    @Id
    private int weekday;
    private LocalTime openTime;
    private LocalTime closeTime;

    protected LibraryHours() {
    }

    public LibraryHours(int weekday, LocalTime openTime, LocalTime closeTime) {
        this.weekday = weekday;
        this.openTime = openTime;
        this.closeTime = closeTime;
    }

    public boolean isOpen() { return openTime != null && closeTime != null; }
    public int getWeekday() { return weekday; }
    public LocalTime getOpenTime() { return openTime; }
    public LocalTime getCloseTime() { return closeTime; }
    public void set(LocalTime open, LocalTime close) { this.openTime = open; this.closeTime = close; }
}

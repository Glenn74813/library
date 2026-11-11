package ph.edu.zscmst.library.calendar;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A date the library is closed (holiday, school event, emergency). */
@Entity
@Table(name = "closed_day")
public class ClosedDay {

    @Id
    private LocalDate day;
    private String reason;

    protected ClosedDay() {
    }

    public ClosedDay(LocalDate day, String reason) {
        this.day = day;
        this.reason = reason;
    }

    public LocalDate getDay() { return day; }
    public String getReason() { return reason; }
}

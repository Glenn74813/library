package ph.edu.zscmst.library.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class PhilippineHolidaysTest {

    @Test
    void easterAndHolyWeek() {
        assertEquals(LocalDate.of(2025, 4, 20), PhilippineHolidays.easter(2025));
        assertEquals(LocalDate.of(2026, 4, 5), PhilippineHolidays.easter(2026));
        assertEquals(LocalDate.of(2027, 3, 28), PhilippineHolidays.easter(2027));
    }

    @Test
    void regularHolidays2026() {
        List<PhilippineHolidays.Holiday> list = PhilippineHolidays.byLaw(2026);
        assertEquals(LocalDate.of(2026, 4, 2), day(list, "Maundy Thursday"));
        assertEquals(LocalDate.of(2026, 4, 3), day(list, "Good Friday"));
        assertEquals(LocalDate.of(2026, 8, 31), day(list, "National Heroes Day"));
        assertEquals(10, list.stream().filter(h -> h.kind() == PhilippineHolidays.Kind.REGULAR).count());
    }

    private static LocalDate day(List<PhilippineHolidays.Holiday> list, String name) {
        return list.stream().filter(h -> h.name().equals(name)).findFirst().orElseThrow().day();
    }
}

package ph.edu.zscmst.library.circulation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import ph.edu.zscmst.library.patron.PatronCategory;

class OverdueFineTest {

    private static PatronCategory category(String perDay, int grace, String cap) {
        PatronCategory c = new PatronCategory("Student");
        c.setFinePerDay(new BigDecimal(perDay));
        c.setFineGraceDays(grace);
        c.setMaxFinePerItem(new BigDecimal(cap));
        return c;
    }

    @Test
    void chargesEveryLateDayWithoutGraceOrCap() {
        assertEquals(new BigDecimal("50.00"), CirculationService.overdueFine(category("5.00", 0, "0"), 10));
    }

    @Test
    void graceDaysAreFree() {
        assertEquals(new BigDecimal("0.00"), CirculationService.overdueFine(category("5.00", 2, "0"), 2));
        assertEquals(new BigDecimal("40.00"), CirculationService.overdueFine(category("5.00", 2, "0"), 10));
    }

    @Test
    void fineIsCappedPerItem() {
        assertEquals(new BigDecimal("100"), CirculationService.overdueFine(category("5.00", 0, "100"), 60));
    }
}

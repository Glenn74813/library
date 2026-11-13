package ph.edu.zscmst.library.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

class NaturalKeyTest {

    private static List<String> sorted(String... names) {
        return List.of(names).stream().sorted(Comparator.comparing(InventoryController::naturalKey)).toList();
    }

    @Test
    void numbersSortByValue() {
        assertEquals(List.of("Aisle 2", "Aisle 3", "Aisle 3A", "Aisle 10"), sorted("Aisle 10", "Aisle 3A", "Aisle 2", "Aisle 3"));
    }

    @Test
    void numbersLongerThanTwelveDigitsStayInOrder() {
        // Used to be cut to 12 digits: the first two compared equal and the 13-digit one sorted before the 12-digit one.
        assertEquals(List.of("999999999999", "1234567890123", "1234567890124"),
                sorted("1234567890124", "999999999999", "1234567890123"));
        assertTrue(InventoryController.naturalKey("1234567890123").compareTo(InventoryController.naturalKey("1234567890124")) < 0);
    }

    @Test
    void leadingZerosDoNotChangeTheValue() {
        assertEquals(InventoryController.naturalKey("Shelf 7"), InventoryController.naturalKey("Shelf 007"));
        assertEquals(List.of("Shelf 0", "Shelf 1"), sorted("Shelf 1", "Shelf 0"));
    }
}

package ph.edu.zscmst.library.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Philippine holidays for a year. Regular holidays and the special non-working days fixed by law are worked
 * out here, so they are always available offline. Days that move each year (Chinese New Year) or are added by
 * proclamation are fetched from a public holiday list (date.nager.at) when the server is online.
 * Eid'l Fitr and Eid'l Adha are regular holidays, but their dates are proclaimed each year, so they are added
 * by hand once announced.
 */
@Service
public class PhilippineHolidays {

    private static final Logger log = LoggerFactory.getLogger(PhilippineHolidays.class);

    public enum Kind { REGULAR, SPECIAL, ONLINE }

    public record Holiday(LocalDate day, String name, Kind kind) {
    }

    private final RestClient http;

    public PhilippineHolidays() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(8000);
        this.http = RestClient.builder().baseUrl("https://date.nager.at").requestFactory(factory).build();
    }

    /** The year's holidays in date order; online ones only where they don't repeat a day already listed. */
    public List<Holiday> forYear(int year) {
        List<Holiday> list = new ArrayList<>(byLaw(year));
        for (Holiday h : online(year)) {
            if (list.stream().noneMatch(x -> x.day().equals(h.day()))) list.add(h);
        }
        list.sort(Comparator.comparing(Holiday::day));
        return list;
    }

    /** Regular holidays (RA 9492 and later laws) and special non-working days set by law. */
    static List<Holiday> byLaw(int year) {
        LocalDate easter = easter(year);
        return List.of(
                new Holiday(LocalDate.of(year, 1, 1), "New Year's Day", Kind.REGULAR),
                new Holiday(easter.minusDays(3), "Maundy Thursday", Kind.REGULAR),
                new Holiday(easter.minusDays(2), "Good Friday", Kind.REGULAR),
                new Holiday(easter.minusDays(1), "Black Saturday", Kind.SPECIAL),
                new Holiday(LocalDate.of(year, 4, 9), "Araw ng Kagitingan", Kind.REGULAR),
                new Holiday(LocalDate.of(year, 5, 1), "Labor Day", Kind.REGULAR),
                new Holiday(LocalDate.of(year, 6, 12), "Independence Day", Kind.REGULAR),
                new Holiday(LocalDate.of(year, 8, 21), "Ninoy Aquino Day", Kind.SPECIAL),
                new Holiday(LocalDate.of(year, 8, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)), "National Heroes Day", Kind.REGULAR),
                new Holiday(LocalDate.of(year, 11, 1), "All Saints' Day", Kind.SPECIAL),
                new Holiday(LocalDate.of(year, 11, 30), "Bonifacio Day", Kind.REGULAR),
                new Holiday(LocalDate.of(year, 12, 8), "Feast of the Immaculate Conception", Kind.SPECIAL),
                new Holiday(LocalDate.of(year, 12, 25), "Christmas Day", Kind.REGULAR),
                new Holiday(LocalDate.of(year, 12, 30), "Rizal Day", Kind.REGULAR),
                new Holiday(LocalDate.of(year, 12, 31), "Last Day of the Year", Kind.SPECIAL));
    }

    /** Easter Sunday (Gregorian calendar, anonymous algorithm). */
    static LocalDate easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(y, month, day);
    }

    private List<Holiday> online(int year) {
        try {
            List<Map<String, Object>> body = http.get().uri("/api/v3/PublicHolidays/{year}/PH", year).retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() { });
            if (body == null) return List.of();
            return body.stream().filter(m -> m.get("date") instanceof String && m.get("name") instanceof String)
                    .map(m -> new Holiday(LocalDate.parse((String) m.get("date")), (String) m.get("name"), Kind.ONLINE)).toList();
        } catch (RuntimeException e) { // offline, refused, or an unexpected answer
            log.warn("Online holiday list for {} not available: {}", year, e.getMessage());
            return List.of();
        }
    }
}

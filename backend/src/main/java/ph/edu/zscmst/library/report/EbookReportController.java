package ph.edu.zscmst.library.report;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.common.Pdf;

/** How school-owned e-books are used: openings per title, distinct readers, and readers by program. */
@RestController
public class EbookReportController {

    public record TitleUsage(Long recordId, String title, String authors, long views, long readers, Instant lastViewed) {
    }

    public record Count(String label, long count) {
    }

    public record EbookUsage(LocalDate from, LocalDate to, long views, long readers, long titlesRead, long titlesAvailable,
                             List<TitleUsage> titles, List<Count> byCourse, List<Count> byDay) {
    }

    private final JdbcTemplate jdbc;
    private final LibraryClock clock;

    public EbookReportController(JdbcTemplate jdbc, LibraryClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/api/reports/ebooks")
    public EbookUsage usage(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate t = to == null ? clock.today() : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        Timestamp start = Timestamp.from(clock.startOfDay(f));
        Timestamp end = Timestamp.from(clock.startOfDay(t.plusDays(1)));
        Long views = jdbc.queryForObject("select count(*) from ebook_view where viewed_at >= ? and viewed_at < ?", Long.class, start, end);
        Long readers = jdbc.queryForObject("select count(distinct patron_id) from ebook_view where viewed_at >= ? and viewed_at < ?",
                Long.class, start, end);
        Long available = jdbc.queryForObject("select count(*) from digital_file", Long.class);
        List<TitleUsage> titles = jdbc.query("""
                select r.id, r.title, r.authors, count(*) as views, count(distinct v.patron_id) as readers, max(v.viewed_at) as last
                from ebook_view v join bib_record r on r.id = v.record_id
                where v.viewed_at >= ? and v.viewed_at < ?
                group by r.id, r.title, r.authors order by views desc, r.title""",
                (rs, i) -> new TitleUsage(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                        rs.getTimestamp(6).toInstant()), start, end);
        List<Count> byCourse = jdbc.query("""
                select coalesce(c.code, case when v.patron_id is null then 'Anonymized' else 'No program' end), count(distinct v.patron_id)
                from ebook_view v left join patron p on p.id = v.patron_id left join course c on c.id = p.course_id
                where v.viewed_at >= ? and v.viewed_at < ?
                group by 1 order by 2 desc""", (rs, i) -> new Count(rs.getString(1), rs.getLong(2)), start, end);
        List<Count> byDay = jdbc.query("""
                select to_char(viewed_at at time zone ?, 'YYYY-MM-DD'), count(*) from ebook_view
                where viewed_at >= ? and viewed_at < ? group by 1 order by 1""",
                (rs, i) -> new Count(rs.getString(1), rs.getLong(2)), clock.zone().getId(), start, end);
        return new EbookUsage(f, t, views == null ? 0 : views, readers == null ? 0 : readers, titles.size(),
                available == null ? 0 : available, titles, byCourse, byDay);
    }

    @GetMapping("/api/reports/export/ebooks")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "csv") String format) {
        EbookUsage u = usage(from, to);
        List<List<?>> rows = u.titles().stream().<List<?>>map(x -> List.of(x.title(), x.authors() == null ? "" : x.authors(),
                x.views(), x.readers(), x.lastViewed().atZone(clock.zone()).toLocalDateTime().withNano(0).toString()
                        .replace('T', ' '))).toList();
        return Pdf.export(format, "ebook-usage-" + u.from() + "-to-" + u.to(), "E-book Usage",
                u.from() + " to " + u.to() + " · " + u.views() + " openings by " + u.readers() + " reader(s)",
                List.of("Title", "Authors", "Openings", "Readers", "Last opened"), rows);
    }
}

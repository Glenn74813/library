package ph.edu.zscmst.library.attendance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AttendanceLogRepository extends JpaRepository<AttendanceLog, Long> {

    Optional<AttendanceLog> findFirstByPatronIdAndTimeInAfterOrderByTimeInDesc(Long patronId, Instant after);

    @EntityGraph(attributePaths = {"patron"})
    Page<AttendanceLog> findByTimeInBetweenOrderByTimeInDesc(Instant from, Instant to, Pageable pageable);

    @EntityGraph(attributePaths = {"patron"})
    List<AttendanceLog> findByTimeInBetweenOrderByTimeInAsc(Instant from, Instant to);

    long countByTimeInBetween(Instant from, Instant to);

    long countByTimeInAfterAndTimeOutIsNull(Instant after);

    @Query("select count(distinct a.patron.id) from AttendanceLog a where a.timeIn >= :from and a.timeIn < :to")
    long countDistinctPatrons(Instant from, Instant to);

    @Query("""
            select coalesce(c.code, 'Unassigned'), count(a) from AttendanceLog a
            join a.patron p left join p.course c
            where a.timeIn >= :from and a.timeIn < :to
            group by c.code order by count(a) desc
            """)
    List<Object[]> countByCourse(Instant from, Instant to, Pageable pageable);

    @Query("""
            select cat.name, count(a) from AttendanceLog a
            join a.patron p join p.category cat
            where a.timeIn >= :from and a.timeIn < :to
            group by cat.name order by count(a) desc
            """)
    List<Object[]> countByCategory(Instant from, Instant to, Pageable pageable);

    @Query(value = """
            SELECT to_char(date_trunc('day', time_in AT TIME ZONE :tz), 'YYYY-MM-DD') AS d, count(*)
            FROM attendance_log WHERE time_in >= :from AND time_in < :to
            GROUP BY d ORDER BY d
            """, nativeQuery = true)
    List<Object[]> countByDay(Instant from, Instant to, String tz);

    @Query(value = """
            SELECT CAST(extract(hour FROM time_in AT TIME ZONE :tz) AS int) AS h, count(*)
            FROM attendance_log WHERE time_in >= :from AND time_in < :to
            GROUP BY h ORDER BY h
            """, nativeQuery = true)
    List<Object[]> countByHour(Instant from, Instant to, String tz);
}

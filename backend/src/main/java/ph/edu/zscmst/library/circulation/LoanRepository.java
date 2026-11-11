package ph.edu.zscmst.library.circulation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    @EntityGraph(attributePaths = {"item", "item.record", "patron"})
    @Query("select l from Loan l where l.item.id in :itemIds and l.returnedAt is null")
    List<Loan> findActiveByItemIds(Collection<Long> itemIds);

    @EntityGraph(attributePaths = {"item", "item.record", "patron"})
    Optional<Loan> findByItemIdAndReturnedAtIsNull(Long itemId);

    @EntityGraph(attributePaths = {"item", "item.record", "patron"})
    Optional<Loan> findWithDetailsById(Long id);

    boolean existsByItemId(Long itemId);

    long countByPatronIdAndReturnedAtIsNull(Long patronId);

    boolean existsByPatronIdAndItemRecordIdAndReturnedAtIsNull(Long patronId, Long recordId);

    @EntityGraph(attributePaths = {"item", "item.record", "patron"})
    List<Loan> findByPatronIdAndReturnedAtIsNullOrderByDueAtAsc(Long patronId);

    @EntityGraph(attributePaths = {"item", "item.record", "patron"})
    Page<Loan> findByPatronIdOrderByCheckoutAtDesc(Long patronId, Pageable pageable);

    @EntityGraph(attributePaths = {"item", "item.record", "patron"})
    Page<Loan> findByReturnedAtIsNullOrderByDueAtAsc(Pageable pageable);

    @EntityGraph(attributePaths = {"item", "item.record", "patron"})
    Page<Loan> findByReturnedAtIsNullAndDueAtBeforeOrderByDueAtAsc(Instant now, Pageable pageable);

    @EntityGraph(attributePaths = {"item", "item.record", "patron"})
    List<Loan> findByReturnedAtIsNullAndDueAtBefore(Instant now);

    @EntityGraph(attributePaths = {"item", "item.record", "patron"})
    List<Loan> findByReturnedAtIsNullAndDueAtBetween(Instant from, Instant to);

    long countByReturnedAtIsNull();

    long countByReturnedAtIsNullAndDueAtBefore(Instant now);

    long countByReturnedAtIsNullAndDueAtBetween(Instant from, Instant to);

    @Query("select count(distinct l.patron.id) from Loan l where l.returnedAt is null and l.patron.expiryDate < :today")
    long countPatronsWithExpiredCardAndLoans(java.time.LocalDate today);

    long countByCheckoutAtBetween(Instant from, Instant to);

    long countByReturnedAtBetween(Instant from, Instant to);

    @Query("""
            select l.patron.id, l.patron.cardNumber, concat(l.patron.firstName, ' ', l.patron.lastName), count(l)
            from Loan l where l.checkoutAt >= :from and l.checkoutAt < :to and l.patron.cardNumber <> '__ANONYMIZED__'
            group by l.patron.id, l.patron.cardNumber, l.patron.firstName, l.patron.lastName
            order by count(l) desc
            """)
    List<Object[]> topBorrowers(Instant from, Instant to, Pageable pageable);

    @Query("""
            select l.item.record.id, l.item.record.title, count(l)
            from Loan l where l.checkoutAt >= :from and l.checkoutAt < :to
            group by l.item.record.id, l.item.record.title
            order by count(l) desc
            """)
    List<Object[]> topTitles(Instant from, Instant to, Pageable pageable);

    @EntityGraph(attributePaths = {"item", "item.record", "patron"})
    List<Loan> findByCheckoutAtBetweenOrderByCheckoutAtAsc(Instant from, Instant to);
}

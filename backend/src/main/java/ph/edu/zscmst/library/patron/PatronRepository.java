package ph.edu.zscmst.library.patron;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PatronRepository extends JpaRepository<Patron, Long> {

    Optional<Patron> findByCardNumberIgnoreCase(String cardNumber);

    /** Row-locks the patron so concurrent requests for the same patron run one after another. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Patron p where p.id = :id")
    Optional<Patron> findByIdForUpdate(Long id);

    /** Resolves a scanned/typed identifier: library card barcode or RFID card UID. */
    @Query("select p from Patron p where lower(p.cardNumber) = lower(:code) or lower(p.rfidUid) = lower(:code)")
    Optional<Patron> findByIdentifier(String code);

    /** Patrons already using a code as card number or RFID UID (any case), to keep every scan unambiguous. */
    @Query("select p.id from Patron p where lower(p.cardNumber) = lower(:code) or lower(p.rfidUid) = lower(:code)")
    java.util.List<Long> idsUsingCode(String code);

    @Query("""
            select p from Patron p
            where p.cardNumber <> '__ANONYMIZED__' and (:q = '' or lower(p.cardNumber) like lower(concat('%', :q, '%'))
               or lower(p.firstName) like lower(concat('%', :q, '%'))
               or lower(p.lastName) like lower(concat('%', :q, '%'))
               or lower(coalesce(p.email, '')) like lower(concat('%', :q, '%')))
            """)
    Page<Patron> search(String q, Pageable pageable);

    long countByAnonymizedAtIsNull();

    /** Patrons for ID-card printing; null filters match everyone. */
    @Query("""
            select p from Patron p
            where p.anonymizedAt is null
              and (:categoryId is null or p.category.id = :categoryId)
              and (:courseId is null or p.course.id = :courseId)
              and p.createdAt >= :since
            order by p.lastName, p.firstName
            """)
    List<Patron> forCards(Long categoryId, Long courseId, java.time.Instant since);

    boolean existsByCategoryId(Long categoryId);

    boolean existsByCourseId(Long courseId);
}

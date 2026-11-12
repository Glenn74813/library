package ph.edu.zscmst.library.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ItemRepository extends JpaRepository<Item, Long> {

    /** Resolves a scanned barcode or RFID EPC. */
    @EntityGraph(attributePaths = "record")
    @Query("select i from Item i where lower(i.barcode) = lower(:code) or lower(i.rfidEpc) = lower(:code)")
    Optional<Item> findByIdentifier(String code);

    /** Items already using a code as barcode or EPC (any case), to keep every scan unambiguous. */
    @Query("select i.id from Item i where lower(i.barcode) = lower(:code) or lower(i.rfidEpc) = lower(:code)")
    List<Long> idsUsingCode(String code);

    /** Row-locks a copy so two desks can't lend or route it at the same moment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.id = :id")
    Optional<Item> findByIdForUpdate(Long id);

    List<Item> findByRecordIdOrderByBarcodeAsc(Long recordId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "record")
    List<Item> findByCreatedAtGreaterThanEqualOrderByBarcodeAsc(java.time.Instant since);

    @EntityGraph(attributePaths = "record")
    List<Item> findByStatusIn(Collection<ItemStatus> statuses);

    @EntityGraph(attributePaths = "record")
    List<Item> findByLocationIgnoreCaseAndStatusIn(String location, Collection<ItemStatus> statuses);

    @Query("select i.record.id, i.status, count(i) from Item i where i.record.id in :ids group by i.record.id, i.status")
    List<Object[]> countByRecordAndStatus(Collection<Long> ids);

    long countByStatus(ItemStatus status);

    boolean existsByBarcodeIgnoreCase(String barcode);

    /** Locks the copies on the shelf of a title, so two holds can't set aside the same copy. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Item> findByRecordIdAndStatusOrderByIdAsc(Long recordId, ItemStatus status);

    /** Locks the copy it returns, so two holds can't set aside the same copy at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Item> findFirstByRecordIdAndStatusOrderByIdAsc(Long recordId, ItemStatus status);

    boolean existsByRecordIdAndStatusNot(Long recordId, ItemStatus status);

    boolean existsByRecordIdAndStatus(Long recordId, ItemStatus status);
}

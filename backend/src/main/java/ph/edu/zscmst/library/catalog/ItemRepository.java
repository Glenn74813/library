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

    List<Item> findByRecordIdOrderByBarcodeAsc(Long recordId);

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

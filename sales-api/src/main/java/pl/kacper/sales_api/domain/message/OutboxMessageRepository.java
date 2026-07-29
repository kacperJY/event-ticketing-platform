package pl.kacper.sales_api.domain.message;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import pl.kacper.sales_api.domain.message.property.MessageStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxMessageRepository extends ListCrudRepository<OutboxMessageEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ome from OutboxMessageEntity ome
            where ome.messageStatus = :status
            and ome.nextAttemptAt <= :nextAttempt
            """)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    })
    List<OutboxMessageEntity> findByStatusAndNextAttemptAtWithLockingSkip(@Param("status") MessageStatus messageStatus, @Param("nextAttempt") Instant nextAttempt, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "0")
    })
    @Query("select ome from OutboxMessageEntity ome where ome.messageId = :messageId and ome.messageStatus = :messageStatus")
    Optional<OutboxMessageEntity> findByStatusAndIDWithLockingNoWait(@Param("messageId") UUID id, @Param("messageStatus") MessageStatus messageStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ome from OutboxMessageEntity ome
            where ome.lockedAt is not null
            and ome.lockedAt < :lockedTimeout
            and ome.messageStatus = pl.kacper.sales_api.domain.message.property.MessageStatus.PROCESSING
            """)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    })
    List<OutboxMessageEntity> findStuckInProcessing(@Param("lockedTimeout") Instant lockedTimeout, Pageable pageable);
    List<OutboxMessageEntity> findStuckInProcessingWithLockingSkip(@Param("lockedTimeout") Instant lockedTimeout, Pageable pageable);
}

package pl.kacper.fulfillment_worker.domain.order;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FulfillmentOrderRepository extends CrudRepository<FulfillmentOrderEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(
                    @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    )
    @Query("""
            select foe from FulfillmentOrderEntity foe where
            (
                        foe.deliveryStatus=pl.kacper.fulfillment_worker.domain.order.DeliveryStatus.PENDING
                        and (foe.nextAttemptAt<=:readyAttemptDate or foe.nextAttemptAt is null )
            )
            or
            (
                        foe.deliveryStatus=pl.kacper.fulfillment_worker.domain.order.DeliveryStatus.PROCESSING
                        and foe.nextAttemptAt is null
                        and foe.attemptCount>0
                        and foe.processingStartedAt<=:outdated
            )
            order by
                        case
                                    when
                                            foe.deliveryStatus=pl.kacper.fulfillment_worker.domain.order.DeliveryStatus.PROCESSING
                                            and foe.nextAttemptAt is null
                                            and foe.attemptCount>0
                                            and foe.processingStartedAt<=:outdated
                                            then 1
                                    when
                                            foe.deliveryStatus=pl.kacper.fulfillment_worker.domain.order.DeliveryStatus.PENDING
                                            and foe.nextAttemptAt<=:readyAttemptDate
                                            then 2
                                    else 3
                       end asc,
                       foe.createdAt asc,
                       foe.sourceOrderId asc

            """)
    List<FulfillmentOrderEntity> findOrderCandidatesForProcessingWithSkipLocked(
            @Param("readyAttemptDate")Instant readyAttemptDate,
            @Param("outdated") Instant outdated,
            Pageable pageable
            );


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(
            @QueryHint(name = "jakarta.persistence.lock.timeout",value = "1000")
    )
    @Query("select foe from FulfillmentOrderEntity foe where foe.sourceOrderId=:orderId")
    Optional<FulfillmentOrderEntity> findBySourceOrderIdWithLockWaiting1000(@Param("orderId") UUID sourceOrderId);
}

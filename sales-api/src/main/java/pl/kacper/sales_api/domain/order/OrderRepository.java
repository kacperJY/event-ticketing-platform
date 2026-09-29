package pl.kacper.sales_api.domain.order;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends ListCrudRepository<OrderEntity, UUID> {

    @EntityGraph(attributePaths = "purchaser")
    Optional<OrderEntity> findOrderEntityWithUserByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    })
    @Query("select order from OrderEntity order WHERE order.expiresAt < :currentTime AND order.orderStatus = :currentOrderStatus")
    List<OrderEntity> findOrdersByOrderStatusAndExpiresAtWithLockingSkipLocked(@Param("currentTime") Instant currentTime, @Param("currentOrderStatus") OrderStatus currentOrderStatus, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "0")
    })
    @Query("select order from OrderEntity order WHERE order.orderId = :orderId AND order.orderStatus = :currentOrderStatus AND order.expiresAt < :currentTime")
    Optional<OrderEntity> findSingleOrderByIdAndStatusAndExpiredAtWithLockingNoWait(@Param("orderId") UUID orderId, @Param("currentTime") Instant currentTime, @Param("currentOrderStatus") OrderStatus currentOrderStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "0")
    })
    @Query("select order from OrderEntity order WHERE order.orderId = :orderId")
    Optional<OrderEntity> findByOrderIdWithLockingNoWait(@Param("orderId") UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(
            {
                    @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
            }
    )
    @Query("""
            select orderEntity from OrderEntity orderEntity
                        where orderEntity.orderStatus=:orderStatus
                                    AND orderEntity.paymentStatus=:paymentStatus
                                                AND orderEntity.stripePaymentIntentId is not null
                                                            AND orderEntity.stripeRefundId is null
                                                                        AND orderEntity.refundedAt is null
                                                                                    AND orderEntity.refundRequestedAt is null
            """)
    List<OrderEntity> findOrderRefundCandidateWithLockingSkipLocked(@Param("orderStatus") OrderStatus orderStatus, @Param("paymentStatus") PaymentStatus paymentStatus, Pageable pageable);
}

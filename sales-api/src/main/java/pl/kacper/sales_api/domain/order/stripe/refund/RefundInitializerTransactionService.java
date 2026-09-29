package pl.kacper.sales_api.domain.order.stripe.refund;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.common.exception.paymentException.RefundStateMismatchException;
import pl.kacper.sales_api.domain.order.OrderEntity;
import pl.kacper.sales_api.domain.order.OrderRepository;
import pl.kacper.sales_api.domain.order.OrderStatus;
import pl.kacper.sales_api.domain.order.PaymentStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class RefundInitializerTransactionService {

    private final OrderRepository orderRepository;

    @Autowired
    RefundInitializerTransactionService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    List<OrderEntity> findOrderRefundCandidates(int batchSize) {
        PageRequest pageRequest = PageRequest.of(0, batchSize);
        return orderRepository.findOrderRefundCandidateWithLockingSkipLocked(
                OrderStatus.EXPIRED,
                PaymentStatus.REFUND_REQUIRED,
                pageRequest
        ); // LOCK
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void validateAndUpdateAfterRefundCreate(UUID orderId, String incomingRefundID) {
        OrderEntity orderEntity = orderRepository.findByOrderIdWithLockingNoWait(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Order with orderId=%s does not exist".formatted(orderId)));

        if (
                orderEntity.getOrderStatus() == OrderStatus.EXPIRED
                        && orderEntity.getPaymentStatus() == PaymentStatus.REFUND_REQUIRED
                        && orderEntity.getRefundedAt() == null
                        && orderEntity.getStripeRefundId() == null
                        && orderEntity.getRefundRequestedAt() == null
        ) {
            orderEntity.setPaymentStatus(PaymentStatus.REFUND_PENDING);
            orderEntity.setStripeRefundId(incomingRefundID);
            orderEntity.setRefundRequestedAt(Instant.now());
            return;
        } else if (
                orderEntity.getOrderStatus() == OrderStatus.EXPIRED
                        && orderEntity.getPaymentStatus() == PaymentStatus.REFUND_PENDING
                        && orderEntity.getRefundedAt() == null
                        && orderEntity.getRefundRequestedAt() != null
                        && orderEntity.getStripeRefundId() != null
                        && orderEntity.getStripeRefundId().equals(incomingRefundID)
        ) return;
        else if (
                orderEntity.getOrderStatus() == OrderStatus.EXPIRED
                        && orderEntity.getPaymentStatus() == PaymentStatus.REFUNDED
                        && orderEntity.getRefundedAt() != null
                        && orderEntity.getRefundRequestedAt() != null
                        && orderEntity.getStripeRefundId() != null
                        && orderEntity.getStripeRefundId().equals(incomingRefundID))
            return;
        else if (
                orderEntity.getOrderStatus() == OrderStatus.EXPIRED
                        && orderEntity.getPaymentStatus() == PaymentStatus.REFUND_FAILED
                        && orderEntity.getRefundedAt() == null
                        && orderEntity.getRefundRequestedAt() != null
                        && orderEntity.getStripeRefundId() != null
                        && orderEntity.getStripeRefundId().equals(incomingRefundID))
            return;

        throw new RefundStateMismatchException(RefundStateMismatchException.RefundMismatchReason.INVALID_LOCAL_REFUND_STATE, """
                Cannot validate order after Refund-Create - INVALID STATE
                OrderId=%s
                OrderStatus=%s
                PaymentStatus=%s
                """.formatted(orderId, orderEntity.getOrderStatus(), orderEntity.getPaymentStatus()));
    }
}

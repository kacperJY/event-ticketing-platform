package pl.kacper.sales_api.domain.order.stripe;

import org.springframework.stereotype.Component;
import pl.kacper.sales_api.common.exception.paymentException.PaymentIntentStateMismatchException;
import pl.kacper.sales_api.common.exception.paymentException.RefundStateMismatchException;
import pl.kacper.sales_api.domain.order.OrderEntity;
import pl.kacper.sales_api.domain.order.PaymentStatus;

@Component
public class PaymentStateValidator {

    void validatePaymentIntent(OrderEntity orderEntity, String incomingPaymentIntentId) {
        if (orderEntity.getPaymentStatus() == PaymentStatus.NOT_INITIALIZED && orderEntity.getStripePaymentIntentId() == null)
            throw new PaymentIntentStateMismatchException(PaymentIntentStateMismatchException.PaymentIntentMismatchReason.LOCAL_PAYMENT_INTENT_MISSING, "Order[orderId=%s]: Invalid local state, probably Order is still waiting to set PaymentID".formatted(orderEntity.getOrderId()));
        else if (orderEntity.getPaymentStatus() != PaymentStatus.NOT_INITIALIZED && orderEntity.getStripePaymentIntentId() != null && !orderEntity.getStripePaymentIntentId().equals(incomingPaymentIntentId))
            throw new PaymentIntentStateMismatchException(PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH, "Order[orderId=%s]: Invalid state between local paymentIntentID=%s and incoming paymentIntentID=%s. \n State of Order payment has to be normalized.".formatted(orderEntity.getOrderId(), orderEntity.getStripePaymentIntentId(), incomingPaymentIntentId));
        else if (orderEntity.getPaymentStatus() == PaymentStatus.NOT_INITIALIZED || orderEntity.getStripePaymentIntentId() == null)
            throw new PaymentIntentStateMismatchException(PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE, "Order[orderId=%s]: Illegal Order state :: OrderStatus=%s - PaymentStatus=%s - PaymentIntentId=%s".formatted(orderEntity.getOrderId(), orderEntity.getOrderStatus(), orderEntity.getPaymentStatus(), orderEntity.getStripePaymentIntentId()));
    }

    void validateRefund(OrderEntity orderEntity, String incomingPaymentIntentId, String incomingStripeRefundId) {
        validatePaymentIntent(orderEntity, incomingPaymentIntentId);
        switch (orderEntity.getPaymentStatus()) {
            case PaymentStatus.REFUND_REQUIRED -> {
                if (orderEntity.getStripeRefundId() == null)
                    throw new RefundStateMismatchException(RefundStateMismatchException.RefundMismatchReason.LOCAL_REFUND_ID_MISSING, "Order[orderId=%s]: Invalid local state, probably Order is still waiting to set RefundID".formatted(orderEntity.getOrderId()));
                throw new RefundStateMismatchException(RefundStateMismatchException.RefundMismatchReason.INVALID_LOCAL_REFUND_STATE,
                        "Order[orderId=%s]: Illegal Order state :: OrderStatus=%s - PaymentStatus=%s - RefundID=%s".formatted(orderEntity.getOrderId(), orderEntity.getOrderStatus(), orderEntity.getPaymentStatus(), orderEntity.getStripeRefundId())
                );
            }
            case REFUND_PENDING, REFUNDED, REFUND_FAILED -> {
                if (orderEntity.getStripeRefundId() == null)
                    throw new RefundStateMismatchException(
                            RefundStateMismatchException.RefundMismatchReason.INVALID_LOCAL_REFUND_STATE,
                            "Order[orderId=%s]: Invalid local state Order cannot be marked as one of [REFUND_PENDING, REFUNDED, REFUND_FAILED] without stripeRefundId, probably inconsistent Order update".formatted(orderEntity.getOrderId())
                    );
                else if (!orderEntity.getStripeRefundId().equals(incomingStripeRefundId))
                    throw new RefundStateMismatchException(
                            RefundStateMismatchException.RefundMismatchReason.REFUND_ID_MISMATCH,
                            "Order[orderId=%s]: Invalid state between local refundId=%s and incoming refundId=%s. \n State of Order payment has to be normalized.".formatted(orderEntity.getOrderId(), orderEntity.getStripeRefundId(), incomingStripeRefundId)
                    );
            }
            default -> {
                if (orderEntity.getStripeRefundId() != null)
                    throw new RefundStateMismatchException(
                            RefundStateMismatchException.RefundMismatchReason.INVALID_LOCAL_REFUND_STATE,
                            "Order[orderId=%s]: Illegal Order state :: OrderStatus=%s - PaymentStatus=%s - RefundId=%s".formatted(orderEntity.getOrderId(), orderEntity.getOrderStatus(), orderEntity.getPaymentStatus(), orderEntity.getStripeRefundId())
                    );
            }
        }
    }
}

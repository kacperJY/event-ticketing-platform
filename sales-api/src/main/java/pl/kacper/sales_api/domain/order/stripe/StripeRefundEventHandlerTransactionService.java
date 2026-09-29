package pl.kacper.sales_api.domain.order.stripe;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.domain.order.OrderEntity;
import pl.kacper.sales_api.domain.order.OrderRepository;
import pl.kacper.sales_api.domain.order.OrderStatus;
import pl.kacper.sales_api.domain.order.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

@Component
public class StripeRefundEventHandlerTransactionService {

    private final OrderRepository orderRepository;
    private final PaymentStateValidator paymentStateValidator;

    StripeRefundEventHandlerTransactionService(OrderRepository orderRepository, PaymentStateValidator paymentStateValidator) {
        this.orderRepository = orderRepository;
        this.paymentStateValidator = paymentStateValidator;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    StripeEventProcessingResult handleFailed(StripeRefundEventContext stripeRefundEventContext) {
        UUID orderId = stripeRefundEventContext.getOrderId();
        String incomingPaymentIntentId = stripeRefundEventContext.getPaymentIntentId();
        String incomingRefundId = stripeRefundEventContext.getStripeRefundId();
        StripeRefundEventContext.StripeRefundStatus stripeRefundStatus = stripeRefundEventContext.getStripeRefundStatus();

        OrderEntity orderEntity = orderRepository.findByOrderIdWithLockingNoWait(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Order with orderId=%s does not exist".formatted(orderId)));

        paymentStateValidator.validateRefund(orderEntity, incomingPaymentIntentId, incomingRefundId);

        return switch (stripeRefundStatus) {
            case FAILED ->
                    handleRefundFailed(stripeRefundEventContext.getStripeWebhookEvent(), stripeRefundStatus, orderEntity, incomingPaymentIntentId, incomingRefundId);
            case SUCCEEDED, CANCELED, REQUIRES_ACTION, PENDING ->
                    handleInvalidStatus(stripeRefundEventContext.getStripeWebhookEvent(), stripeRefundStatus, orderEntity, incomingPaymentIntentId, incomingRefundId);
        };
    }

    private StripeEventProcessingResult handleRefundFailed(StripeWebhookEvent stripeWebhookEvent, StripeRefundEventContext.StripeRefundStatus stripeRefundStatus, OrderEntity orderEntity, String incomingPaymentIntentId, String incomingRefundId) {
        return switch (orderEntity.getOrderStatus()) {
            case PENDING, CANCELED, COMPLETED -> new StripeEventProcessingResult(
                    StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                    MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, orderEntity.getOrderStatus(), orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Inconsistent pair of statuses"));
            case EXPIRED -> {
                if (orderEntity.getPaymentStatus() == PaymentStatus.REFUNDED) {
                    orderEntity.setRefundedAt(null);
                    orderEntity.setPaymentStatus(PaymentStatus.REFUND_FAILED);
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.APPLIED,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Order refund finished with failure. Order refund has been marked as REFUNDED before. Rollback for previous refund SUCCEEDED and marked as FAILED"));
                } else if (orderEntity.getPaymentStatus() == PaymentStatus.REFUND_PENDING) {
                    orderEntity.setPaymentStatus(PaymentStatus.REFUND_FAILED);
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.APPLIED,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Order refund has ended with failure or cancellation. Order's payment will be marked as REFUND_FAILED"));
                } else if (orderEntity.getPaymentStatus() == PaymentStatus.REFUND_FAILED)
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.IDEMPOTENT_NO_OP,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Order payment has been already marked as REFUND_FAILED, no local state transition required"));
                yield new StripeEventProcessingResult(
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                        MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Inconsistent pair of statuses"));
            }
        };
    }

    private StripeEventProcessingResult handleInvalidStatus(StripeWebhookEvent stripeWebhookEvent, StripeRefundEventContext.StripeRefundStatus stripeRefundStatus, OrderEntity orderEntity, String incomingPaymentIntentId, String incomingRefundId) {
        return new StripeEventProcessingResult(StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, orderEntity.getOrderStatus(), orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Inconsistent pair StripeEvent and EventStatus [REFUND.FAILED <-> %s]".formatted(stripeRefundStatus)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    StripeEventProcessingResult handleCreated(StripeRefundEventContext stripeRefundEventContext) {
        UUID orderId = stripeRefundEventContext.getOrderId();
        String incomingPaymentIntentId = stripeRefundEventContext.getPaymentIntentId();
        String incomingRefundId = stripeRefundEventContext.getStripeRefundId();
        StripeRefundEventContext.StripeRefundStatus stripeRefundStatus = stripeRefundEventContext.getStripeRefundStatus();

        OrderEntity orderEntity = orderRepository.findByOrderIdWithLockingNoWait(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Order with orderId=%s does not exist".formatted(orderId)));

        paymentStateValidator.validateRefund(orderEntity, incomingPaymentIntentId, incomingRefundId);

        return switch (stripeRefundStatus) {
            case SUCCEEDED ->
                    handleRefundSucceeded(stripeRefundEventContext.getStripeWebhookEvent(), stripeRefundStatus, orderEntity, incomingPaymentIntentId, incomingRefundId);
            case FAILED, CANCELED ->
                    handleRefundFailedAndCanceled(stripeRefundEventContext.getStripeWebhookEvent(), stripeRefundStatus, orderEntity, incomingPaymentIntentId, incomingRefundId);
            case REQUIRES_ACTION ->
                    handleRefundRequiresAction(stripeRefundEventContext.getStripeWebhookEvent(), stripeRefundStatus, orderEntity, incomingPaymentIntentId, incomingRefundId);
            case PENDING ->
                    handleRefundPending(stripeRefundEventContext.getStripeWebhookEvent(), stripeRefundStatus, orderEntity, incomingPaymentIntentId, incomingRefundId);
        };
    }

    @Transactional(propagation = Propagation.MANDATORY)
    StripeEventProcessingResult handleUpdated(StripeRefundEventContext stripeRefundEventContext) {
        UUID orderId = stripeRefundEventContext.getOrderId();
        String incomingPaymentIntentId = stripeRefundEventContext.getPaymentIntentId();
        String incomingRefundId = stripeRefundEventContext.getStripeRefundId();
        StripeRefundEventContext.StripeRefundStatus stripeRefundStatus = stripeRefundEventContext.getStripeRefundStatus();

        OrderEntity orderEntity = orderRepository.findByOrderIdWithLockingNoWait(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Order with orderId=%s does not exist".formatted(orderId)));

        paymentStateValidator.validateRefund(orderEntity, incomingPaymentIntentId, incomingRefundId);

        return switch (stripeRefundStatus) {
            case SUCCEEDED ->
                    handleRefundSucceeded(stripeRefundEventContext.getStripeWebhookEvent(), stripeRefundStatus, orderEntity, incomingPaymentIntentId, incomingRefundId);
            case FAILED, CANCELED ->
                    handleRefundFailedAndCanceled(stripeRefundEventContext.getStripeWebhookEvent(), stripeRefundStatus, orderEntity, incomingPaymentIntentId, incomingRefundId);
            case REQUIRES_ACTION ->
                    handleRefundRequiresAction(stripeRefundEventContext.getStripeWebhookEvent(), stripeRefundStatus, orderEntity, incomingPaymentIntentId, incomingRefundId);
            case PENDING ->
                    handleRefundPending(stripeRefundEventContext.getStripeWebhookEvent(), stripeRefundStatus, orderEntity, incomingPaymentIntentId, incomingRefundId);
        };
    }

    private StripeEventProcessingResult handleRefundRequiresAction(StripeWebhookEvent stripeWebhookEvent, StripeRefundEventContext.StripeRefundStatus stripeRefundStatus, OrderEntity orderEntity, String incomingPaymentIntentId, String incomingRefundId) {
        return switch (orderEntity.getOrderStatus()) {
            case PENDING, CANCELED, COMPLETED -> new StripeEventProcessingResult(
                    StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                    MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, orderEntity.getOrderStatus(), orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Inconsistent pair of statuses"));
            case EXPIRED -> {
                if (orderEntity.getPaymentStatus() == PaymentStatus.REFUNDED)
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Taking action for the payment refund is not possible, because payment has been already refunded successfully"));
                else if (orderEntity.getPaymentStatus() == PaymentStatus.REFUND_PENDING) {
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.IDEMPOTENT_NO_OP,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Taking action for the payment refund is not possible - REFUND PENDING"));
                } else if (orderEntity.getPaymentStatus() == PaymentStatus.REFUND_FAILED)
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Taking action for the payment refund is not possible, because payment has been already ended with refund failure"));

                yield new StripeEventProcessingResult(
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                        MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Inconsistent pair of statuses"));
            }
        };
    }

    private StripeEventProcessingResult handleRefundFailedAndCanceled(StripeWebhookEvent stripeWebhookEvent, StripeRefundEventContext.StripeRefundStatus stripeRefundStatus, OrderEntity orderEntity, String incomingPaymentIntentId, String incomingRefundId) {
        return switch (orderEntity.getOrderStatus()) {
            case PENDING, CANCELED, COMPLETED -> new StripeEventProcessingResult(
                    StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                    MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, orderEntity.getOrderStatus(), orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Inconsistent pair of statuses"));
            case EXPIRED -> {
                if (orderEntity.getPaymentStatus() == PaymentStatus.REFUNDED)
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Order payment cannot be marked as FAILED if payment has been refunded successfully first"));
                else if (orderEntity.getPaymentStatus() == PaymentStatus.REFUND_PENDING) {
                    orderEntity.setPaymentStatus(PaymentStatus.REFUND_FAILED);
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.APPLIED,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Order refund has ended with failure or cancellation. Order's payment will be marked as REFUND_FAILED"));
                } else if (orderEntity.getPaymentStatus() == PaymentStatus.REFUND_FAILED)
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.IDEMPOTENT_NO_OP,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Order payment has been already marked as REFUND_FAILED, no local state transition required"));

                yield new StripeEventProcessingResult(
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                        MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Inconsistent pair of statuses"));
            }
        };
    }

    private StripeEventProcessingResult handleRefundSucceeded(StripeWebhookEvent stripeWebhookEvent, StripeRefundEventContext.StripeRefundStatus stripeRefundStatus, OrderEntity orderEntity, String incomingPaymentIntentId, String incomingRefundId) {
        return switch (orderEntity.getOrderStatus()) {
            case PENDING, CANCELED, COMPLETED -> new StripeEventProcessingResult(
                    StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                    MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, orderEntity.getOrderStatus(), orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Inconsistent pair of statuses"));
            case EXPIRED -> {
                if (orderEntity.getPaymentStatus() == PaymentStatus.REFUNDED)
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.IDEMPOTENT_NO_OP,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Order payment has been already refunded, no local state transition required"));
                else if (orderEntity.getPaymentStatus() == PaymentStatus.REFUND_PENDING) {
                    orderEntity.setPaymentStatus(PaymentStatus.REFUNDED);
                    orderEntity.setRefundedAt(Instant.now());
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.APPLIED,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Order refund successfully processed"));
                } else if (orderEntity.getPaymentStatus() == PaymentStatus.REFUND_FAILED)
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Order payment cannot be refunded successfully if payment has been ended with refund failure first"));

                yield new StripeEventProcessingResult(
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                        MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Inconsistent pair of statuses"));
            }
        };
    }

    private StripeEventProcessingResult handleRefundPending(StripeWebhookEvent stripeWebhookEvent, StripeRefundEventContext.StripeRefundStatus stripeRefundStatus, OrderEntity orderEntity, String incomingPaymentIntentId, String incomingRefundId) {
        return switch (orderEntity.getOrderStatus()) {
            case PENDING, CANCELED, COMPLETED -> new StripeEventProcessingResult(
                    StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                    MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, orderEntity.getOrderStatus(), orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Inconsistent pair of statuses"));
            case EXPIRED -> {
                if (orderEntity.getPaymentStatus() == PaymentStatus.REFUNDED)
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Order payment has been already refunded, cannot try to refund the same payment again"));
                else if (orderEntity.getPaymentStatus() == PaymentStatus.REFUND_PENDING) {
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.IDEMPOTENT_NO_OP,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Order refund is already processing, no local state transition required"));
                } else if (orderEntity.getPaymentStatus() == PaymentStatus.REFUND_FAILED)
                    yield new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Order payment refund has been already ended with failure, cannot try to refund the same payment again"));

                yield new StripeEventProcessingResult(
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                        MessageBuilder.buildMessage(stripeWebhookEvent, stripeRefundStatus, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), incomingPaymentIntentId, incomingRefundId, "Inconsistent pair of statuses"));
            }
        };
    }


    private static class MessageBuilder {
        static String buildMessage(StripeWebhookEvent stripeWebhookEvent, StripeRefundEventContext.StripeRefundStatus refundStatus, OrderStatus orderStatus, PaymentStatus paymentStatus, String paymentIntentId, String refundId, String message) {
            return "Event=%s & RefundStatus=%s :: %s :: OrderStatus=%s & PaymentStatus=%s & paymentId=%s & refundId=%s".formatted(stripeWebhookEvent, refundStatus, message, orderStatus, paymentStatus, paymentIntentId, refundId);
        }
    }
}

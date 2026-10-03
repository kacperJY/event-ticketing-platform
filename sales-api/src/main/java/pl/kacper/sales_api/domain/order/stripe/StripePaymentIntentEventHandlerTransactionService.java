package pl.kacper.sales_api.domain.order.stripe;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.domain.order.*;

import java.time.Instant;
import java.util.UUID;

@Component
public class StripePaymentIntentEventHandlerTransactionService {

    private final OrderRepository orderRepository;
    private final OrderLifecycleService orderLifecycleService;
    private final PaymentStateValidator paymentStateValidator;
    private final OrderFulfillmentOutboxService orderFulfillmentOutboxService;

    @Autowired
    StripePaymentIntentEventHandlerTransactionService(OrderRepository orderRepository, OrderLifecycleService orderLifecycleService,
                                                      PaymentStateValidator paymentStateValidator, OrderFulfillmentOutboxService orderFulfillmentOutboxService) {
        this.orderRepository = orderRepository;
        this.orderLifecycleService = orderLifecycleService;
        this.paymentStateValidator = paymentStateValidator;
        this.orderFulfillmentOutboxService = orderFulfillmentOutboxService;
    }

    StripeEventProcessingResult validatePaymentIntentIdForFailedEvent(StripeEventContext stripeEventContext) {
        UUID orderId = stripeEventContext.getOrderId();
        String incomingPaymentIntentId = stripeEventContext.getPaymentIntentId();

        OrderEntity orderEntity = orderRepository.findByOrderIdWithLockingNoWait(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Order with orderId=%s does not exist".formatted(orderId)));

        paymentStateValidator.validatePaymentIntent(orderEntity, incomingPaymentIntentId);

        return new StripeEventProcessingResult(
                StripeEventProcessingResult.State.IDEMPOTENT_NO_OP,
                MessageBuilder.buildMessage(StripeWebhookEvent.PI_FAILED, "no local state transition required"));
    }


    @Transactional(propagation = Propagation.MANDATORY)
    StripeEventProcessingResult handleSucceed(StripeEventContext stripeEventContext) {
        UUID orderId = stripeEventContext.getOrderId();
        String incomingPaymentIntentId = stripeEventContext.getPaymentIntentId();

        OrderEntity orderEntity = orderRepository.findByOrderIdWithLockingNoWait(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Order with orderId=%s does not exist".formatted(orderId)));

        paymentStateValidator.validatePaymentIntent(orderEntity, incomingPaymentIntentId);

        switch (orderEntity.getOrderStatus()) {
            case PENDING -> {
                if (orderEntity.getPaymentStatus() == PaymentStatus.PENDING) {
                    orderEntity.setOrderStatus(OrderStatus.COMPLETED);
                    orderEntity.setPaymentStatus(PaymentStatus.SUCCEEDED);
                    orderEntity.setPaidAt(Instant.now());
                    orderLifecycleService.cleanOrderSeatsForCompleted(orderId);
                    orderFulfillmentOutboxService.createFulfillmentOutboxMessage(orderEntity);
                } else
                    return new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(StripeWebhookEvent.PI_SUCCEEDED, OrderStatus.PENDING, orderEntity.getPaymentStatus(), "Inconsistent pair of statuses"));
            }
            case COMPLETED -> {
                if (orderEntity.getPaymentStatus() != PaymentStatus.SUCCEEDED)
                    return new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(StripeWebhookEvent.PI_SUCCEEDED, OrderStatus.COMPLETED, orderEntity.getPaymentStatus(), "Inconsistent pair of statuses")
                    );
                return new StripeEventProcessingResult(
                        StripeEventProcessingResult.State.IDEMPOTENT_NO_OP,
                        MessageBuilder.buildMessage(StripeWebhookEvent.PI_SUCCEEDED, "Order has already been completed successfully, no local state transition required"));
            }
            case CANCELED -> {
                if (orderEntity.getPaymentStatus() == PaymentStatus.CANCELED)
                    return new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(StripeWebhookEvent.PI_SUCCEEDED, OrderStatus.CANCELED, orderEntity.getPaymentStatus(), "Order cannot be COMPLETED successfully if the same order has been CANCELED first, no local state transition required"));

                return new StripeEventProcessingResult(
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                        MessageBuilder.buildMessage(StripeWebhookEvent.PI_SUCCEEDED, OrderStatus.CANCELED, orderEntity.getPaymentStatus(), "Inconsistent pair of statuses"));
            }
            case EXPIRED -> {
                if (orderEntity.getPaymentStatus() == PaymentStatus.PENDING) {
                    orderEntity.setPaymentStatus(PaymentStatus.REFUND_REQUIRED);
                    orderEntity.setPaidAt(Instant.now());
                    return new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.APPLIED,
                            MessageBuilder.buildMessage(StripeWebhookEvent.PI_SUCCEEDED, "Order is expired so it cannot be completed successfully. Payment is marked as REFUND_REQUIRED"));
                } else if (orderEntity.getPaymentStatus() != PaymentStatus.REFUND_REQUIRED && orderEntity.getPaymentStatus() != PaymentStatus.REFUNDED &&
                        orderEntity.getPaymentStatus() != PaymentStatus.REFUND_PENDING && orderEntity.getPaymentStatus() != PaymentStatus.REFUND_FAILED)
                    return new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(StripeWebhookEvent.PI_SUCCEEDED, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), "Inconsistent pair of statuses"));

                return new StripeEventProcessingResult(
                        StripeEventProcessingResult.State.IDEMPOTENT_NO_OP,
                        MessageBuilder.buildMessage(StripeWebhookEvent.PI_SUCCEEDED, "Order cannot be completed successfully because payment has already been refunded or marked as REFUND_REQUIRED"));
            }
        }
        return new StripeEventProcessingResult(StripeEventProcessingResult.State.APPLIED, MessageBuilder.buildMessage(StripeWebhookEvent.PI_SUCCEEDED, "Order payment successfully processed"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    StripeEventProcessingResult handleCanceled(StripeEventContext stripeEventContext) {
        UUID orderId = stripeEventContext.getOrderId();
        String incomingPaymentIntentId = stripeEventContext.getPaymentIntentId();

        OrderEntity orderEntity = orderRepository.findByOrderIdWithLockingNoWait(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Order with orderId=%s does not exist".formatted(orderId)));

        paymentStateValidator.validatePaymentIntent(orderEntity, incomingPaymentIntentId);

        switch (orderEntity.getOrderStatus()) {
            case PENDING -> {
                if (orderEntity.getPaymentStatus() == PaymentStatus.PENDING) {
                    orderEntity.setOrderStatus(OrderStatus.CANCELED);
                    orderEntity.setPaymentStatus(PaymentStatus.CANCELED);
                    orderLifecycleService.cleanOrderSeatsForCanceled(orderId);
                } else return new StripeEventProcessingResult(
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                        MessageBuilder.buildMessage(StripeWebhookEvent.PI_CANCELED, OrderStatus.PENDING, orderEntity.getPaymentStatus(), "Inconsistent pair of statuses"));
            }
            case CANCELED -> {
                if (orderEntity.getPaymentStatus() != PaymentStatus.CANCELED)
                    return new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(StripeWebhookEvent.PI_CANCELED, OrderStatus.CANCELED, orderEntity.getPaymentStatus(), "Inconsistent pair of statuses")
                    );
                return new StripeEventProcessingResult(
                        StripeEventProcessingResult.State.IDEMPOTENT_NO_OP,
                        MessageBuilder.buildMessage(StripeWebhookEvent.PI_CANCELED, "Payment has already been canceled, no local state transition required"));
            }
            case COMPLETED -> {
                if (orderEntity.getPaymentStatus() == PaymentStatus.SUCCEEDED)
                    return new StripeEventProcessingResult(StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT, "Inconsistent payment state. Payment cannot be canceled if the same payment has been succeeded first");
                else
                    return new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(StripeWebhookEvent.PI_CANCELED, OrderStatus.COMPLETED, orderEntity.getPaymentStatus(), "Inconsistent pair of statuses"));
            }
            case EXPIRED -> {
                if (orderEntity.getPaymentStatus() == PaymentStatus.PENDING)
                    orderEntity.setPaymentStatus(PaymentStatus.CANCELED);
                else if (orderEntity.getPaymentStatus() == PaymentStatus.CANCELED)
                    return new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.IDEMPOTENT_NO_OP,
                            MessageBuilder.buildMessage(StripeWebhookEvent.PI_CANCELED, "Payment has already been canceled, no local state transition required"));
                else
                    return new StripeEventProcessingResult(
                            StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT,
                            MessageBuilder.buildMessage(StripeWebhookEvent.PI_CANCELED, OrderStatus.EXPIRED, orderEntity.getPaymentStatus(), "Inconsistent pair of statuses"));
            }
        }
        return new StripeEventProcessingResult(StripeEventProcessingResult.State.APPLIED, MessageBuilder.buildMessage(StripeWebhookEvent.PI_CANCELED, "Order payment has been successfully canceled"));
    }

    private static class MessageBuilder {

        static String buildMessage(StripeWebhookEvent stripeWebhookEvent, OrderStatus orderStatus, PaymentStatus paymentStatus, String message) {
            return "%s :: %s :: OrderStatus=%s & PaymentStatus=%s".formatted(stripeWebhookEvent.getValue(), message, orderStatus, paymentStatus);
        }

        static String buildMessage(StripeWebhookEvent stripeWebhookEvent, String message) {
            return "%s :: %s".formatted(stripeWebhookEvent.getValue(), message);
        }
    }
}
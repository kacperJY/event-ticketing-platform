package pl.kacper.sales_api.domain.order.stripe;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.domain.order.*;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
public class StripePaymentIntentEventHandlerTransactionServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderLifecycleService orderLifecycleService;

    @Mock
    private PaymentStateValidator paymentStateValidator;

    @Mock
    private OrderFulfillmentOutboxService orderFulfillmentOutboxService;

    @InjectMocks
    private StripePaymentIntentEventHandlerTransactionService stripePaymentIntentEventHandlerTransactionService;


    // =========================================================
    // SUCCEEDED EVENT
    // =========================================================

    @ParameterizedTest(name = "[{index}] {0}/{1} -> {2}/{3}, result={4}")
    @MethodSource("succeedCases")
    void shouldHandleSucceedEventAccordingToStateMachine(
            OrderStatus initialOrderStatus,
            PaymentStatus initialPaymentStatus,
            OrderStatus expectedOrderStatus,
            PaymentStatus expectedPaymentStatus,
            StripeEventProcessingResult.State expectedResult
    ) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(initialOrderStatus);
        orderEntity.setPaymentStatus(initialPaymentStatus);

        String paymentIntentId = "pi_123";
        orderEntity.setStripePaymentIntentId(paymentIntentId);

        UUID orderId = UUID.randomUUID();

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId))
                .thenReturn(Optional.of(orderEntity));

        StripeEventProcessingResult result =
                stripePaymentIntentEventHandlerTransactionService.handleSucceed(
                        new StripeEventContext(orderId,
                                paymentIntentId, StripeWebhookEvent.PI_SUCCEEDED)
                );

        Assertions.assertThat(result.state())
                .isEqualTo(expectedResult);

        Assertions.assertThat(orderEntity.getOrderStatus())
                .isEqualTo(expectedOrderStatus);

        Assertions.assertThat(orderEntity.getPaymentStatus())
                .isEqualTo(expectedPaymentStatus);

        // Check create outbox
        int timesOfInvokeCreateOutbox = initialOrderStatus == OrderStatus.PENDING && initialPaymentStatus == PaymentStatus.PENDING ? 1 : 0;
        Mockito.verify(orderFulfillmentOutboxService, Mockito.times(timesOfInvokeCreateOutbox)).createFulfillmentOutboxMessage(orderEntity);
    }

    static Stream<Arguments> succeedCases() {
        return Stream.of(
                // PENDING
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.PENDING,
                        OrderStatus.COMPLETED,
                        PaymentStatus.SUCCEEDED,
                        StripeEventProcessingResult.State.APPLIED
                ),
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.SUCCEEDED,
                        OrderStatus.PENDING,
                        PaymentStatus.SUCCEEDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.CANCELED,
                        OrderStatus.PENDING,
                        PaymentStatus.CANCELED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.REFUND_REQUIRED,
                        OrderStatus.PENDING,
                        PaymentStatus.REFUND_REQUIRED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.REFUND_PENDING,
                        OrderStatus.PENDING,
                        PaymentStatus.REFUND_PENDING,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.REFUND_FAILED,
                        OrderStatus.PENDING,
                        PaymentStatus.REFUND_FAILED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.REFUNDED,
                        OrderStatus.PENDING,
                        PaymentStatus.REFUNDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),

                // COMPLETED
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.SUCCEEDED,
                        OrderStatus.COMPLETED,
                        PaymentStatus.SUCCEEDED,
                        StripeEventProcessingResult.State.IDEMPOTENT_NO_OP
                ),
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.PENDING,
                        OrderStatus.COMPLETED,
                        PaymentStatus.PENDING,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.CANCELED,
                        OrderStatus.COMPLETED,
                        PaymentStatus.CANCELED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUND_REQUIRED,
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUND_REQUIRED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUND_PENDING,
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUND_PENDING,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUND_FAILED,
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUND_FAILED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUNDED,
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUNDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),

                // CANCELED
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.PENDING,
                        OrderStatus.CANCELED,
                        PaymentStatus.PENDING,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.SUCCEEDED,
                        OrderStatus.CANCELED,
                        PaymentStatus.SUCCEEDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.CANCELED,
                        OrderStatus.CANCELED,
                        PaymentStatus.CANCELED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUND_REQUIRED,
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUND_REQUIRED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUND_PENDING,
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUND_PENDING,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUND_FAILED,
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUND_FAILED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUNDED,
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUNDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),

                // EXPIRED
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.PENDING,
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_REQUIRED,
                        StripeEventProcessingResult.State.APPLIED
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_PENDING,
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_PENDING,
                        StripeEventProcessingResult.State.IDEMPOTENT_NO_OP
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_FAILED,
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_FAILED,
                        StripeEventProcessingResult.State.IDEMPOTENT_NO_OP
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_REQUIRED,
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_REQUIRED,
                        StripeEventProcessingResult.State.IDEMPOTENT_NO_OP
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUNDED,
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUNDED,
                        StripeEventProcessingResult.State.IDEMPOTENT_NO_OP
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.SUCCEEDED,
                        OrderStatus.EXPIRED,
                        PaymentStatus.SUCCEEDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.CANCELED,
                        OrderStatus.EXPIRED,
                        PaymentStatus.CANCELED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                )
        );
    }


    // =========================================================
    // CANCELED EVENT
    // =========================================================

    @ParameterizedTest(name = "[{index}] {0}/{1} -> {2}/{3}, result={4}")
    @MethodSource("canceledCases")
    void shouldHandleCanceledEventAccordingToStateMachine(
            OrderStatus initialOrderStatus,
            PaymentStatus initialPaymentStatus,
            OrderStatus expectedOrderStatus,
            PaymentStatus expectedPaymentStatus,
            StripeEventProcessingResult.State expectedResult
    ) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(initialOrderStatus);
        orderEntity.setPaymentStatus(initialPaymentStatus);

        String paymentIntentId = "pi_123";
        orderEntity.setStripePaymentIntentId(paymentIntentId);

        UUID orderId = UUID.randomUUID();

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId))
                .thenReturn(Optional.of(orderEntity));

        StripeEventProcessingResult result =
                stripePaymentIntentEventHandlerTransactionService.handleCanceled(
                        new StripeEventContext(orderId,
                                paymentIntentId, StripeWebhookEvent.PI_CANCELED)
                );

        Assertions.assertThat(result.state())
                .isEqualTo(expectedResult);

        Assertions.assertThat(orderEntity.getOrderStatus())
                .isEqualTo(expectedOrderStatus);

        Assertions.assertThat(orderEntity.getPaymentStatus())
                .isEqualTo(expectedPaymentStatus);
    }

    static Stream<Arguments> canceledCases() {
        return Stream.of(
                // PENDING
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.PENDING,
                        OrderStatus.CANCELED,
                        PaymentStatus.CANCELED,
                        StripeEventProcessingResult.State.APPLIED
                ),
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.SUCCEEDED,
                        OrderStatus.PENDING,
                        PaymentStatus.SUCCEEDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.CANCELED,
                        OrderStatus.PENDING,
                        PaymentStatus.CANCELED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.REFUND_REQUIRED,
                        OrderStatus.PENDING,
                        PaymentStatus.REFUND_REQUIRED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.REFUND_PENDING,
                        OrderStatus.PENDING,
                        PaymentStatus.REFUND_PENDING,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.REFUND_FAILED,
                        OrderStatus.PENDING,
                        PaymentStatus.REFUND_FAILED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.PENDING,
                        PaymentStatus.REFUNDED,
                        OrderStatus.PENDING,
                        PaymentStatus.REFUNDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),

                // CANCELED
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.CANCELED,
                        OrderStatus.CANCELED,
                        PaymentStatus.CANCELED,
                        StripeEventProcessingResult.State.IDEMPOTENT_NO_OP
                ),
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.PENDING,
                        OrderStatus.CANCELED,
                        PaymentStatus.PENDING,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.SUCCEEDED,
                        OrderStatus.CANCELED,
                        PaymentStatus.SUCCEEDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUND_REQUIRED,
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUND_REQUIRED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUND_PENDING,
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUND_PENDING,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUND_FAILED,
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUND_FAILED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUNDED,
                        OrderStatus.CANCELED,
                        PaymentStatus.REFUNDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                // COMPLETED
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.PENDING,
                        OrderStatus.COMPLETED,
                        PaymentStatus.PENDING,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.SUCCEEDED,
                        OrderStatus.COMPLETED,
                        PaymentStatus.SUCCEEDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.CANCELED,
                        OrderStatus.COMPLETED,
                        PaymentStatus.CANCELED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUND_REQUIRED,
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUND_REQUIRED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUND_PENDING,
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUND_PENDING,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUND_FAILED,
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUND_FAILED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUNDED,
                        OrderStatus.COMPLETED,
                        PaymentStatus.REFUNDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),

                // EXPIRED
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.PENDING,
                        OrderStatus.EXPIRED,
                        PaymentStatus.CANCELED,
                        StripeEventProcessingResult.State.APPLIED
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.CANCELED,
                        OrderStatus.EXPIRED,
                        PaymentStatus.CANCELED,
                        StripeEventProcessingResult.State.IDEMPOTENT_NO_OP
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.SUCCEEDED,
                        OrderStatus.EXPIRED,
                        PaymentStatus.SUCCEEDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_REQUIRED,
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_REQUIRED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_PENDING,
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_PENDING,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_FAILED,
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_FAILED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUNDED,
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUNDED,
                        StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                )
        );
    }


    // =========================================================
    // FAILED EVENT
    // =========================================================

    @Test
    void shouldReturnIdempotentNoOpAndNotChangeStateForFailedEvent() {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);

        String paymentIntentId = "pi_123";
        orderEntity.setStripePaymentIntentId(paymentIntentId);

        UUID orderId = UUID.randomUUID();

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId))
                .thenReturn(Optional.of(orderEntity));

        StripeEventProcessingResult result =
                stripePaymentIntentEventHandlerTransactionService.validatePaymentIntentIdForFailedEvent(
                        new StripeEventContext(orderId,
                                paymentIntentId, StripeWebhookEvent.PI_FAILED)
                );

        Assertions.assertThat(result.state())
                .isEqualTo(StripeEventProcessingResult.State.IDEMPOTENT_NO_OP);

        Assertions.assertThat(orderEntity.getOrderStatus())
                .isEqualTo(OrderStatus.PENDING);

        Assertions.assertThat(orderEntity.getPaymentStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }


    // =========================================================
    // PAYMENT INTENT CORRELATION
    // =========================================================


    @Test
    void shouldThrowWhenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId))
                .thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() ->
                        stripePaymentIntentEventHandlerTransactionService.handleSucceed(
                                new StripeEventContext(orderId,
                                        "pi_123", StripeWebhookEvent.PI_SUCCEEDED)
                        ))
                .isInstanceOf(NoSuchDbRecordException.class);
    }
}

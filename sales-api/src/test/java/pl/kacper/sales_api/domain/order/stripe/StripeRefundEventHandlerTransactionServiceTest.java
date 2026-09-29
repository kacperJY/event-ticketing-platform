package pl.kacper.sales_api.domain.order.stripe;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.domain.order.OrderEntity;
import pl.kacper.sales_api.domain.order.OrderRepository;
import pl.kacper.sales_api.domain.order.OrderStatus;
import pl.kacper.sales_api.domain.order.PaymentStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
public class StripeRefundEventHandlerTransactionServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentStateValidator paymentStateValidator;

    @InjectMocks
    private StripeRefundEventHandlerTransactionService stripeRefundEventHandlerTransactionService;


    // =========================================================
    // SUCCEEDED
    // =========================================================

    @ParameterizedTest(name = "[{index}] {0}/{1} -> {2}, result={3}")
    @MethodSource("succeedCases")
    void shouldHandleSucceededRefundAccordingToStateMachine(
            OrderStatus initialOrderStatus,
            PaymentStatus initialPaymentStatus,
            PaymentStatus expectedPaymentStatus,
            StripeEventProcessingResult.State expectedResult
    ) {
        executeAndAssert(
                StripeRefundEventContext.StripeRefundStatus.SUCCEEDED,
                initialOrderStatus,
                initialPaymentStatus,
                expectedPaymentStatus,
                expectedResult
        );
    }


    // =========================================================
    // PENDING
    // =========================================================

    @ParameterizedTest(name = "[{index}] {0}/{1} -> {2}, result={3}")
    @MethodSource("pendingCases")
    void shouldHandlePendingRefundAccordingToStateMachine(
            OrderStatus initialOrderStatus,
            PaymentStatus initialPaymentStatus,
            PaymentStatus expectedPaymentStatus,
            StripeEventProcessingResult.State expectedResult
    ) {
        executeAndAssert(
                StripeRefundEventContext.StripeRefundStatus.PENDING,
                initialOrderStatus,
                initialPaymentStatus,
                expectedPaymentStatus,
                expectedResult
        );
    }


    // =========================================================
    // FAILED
    // =========================================================

    @ParameterizedTest(name = "[{index}] {0}/{1} -> {2}, result={3}")
    @MethodSource("failedAndCanceledCases")
    void shouldHandleFailedRefundAccordingToStateMachine(
            OrderStatus initialOrderStatus,
            PaymentStatus initialPaymentStatus,
            PaymentStatus expectedPaymentStatus,
            StripeEventProcessingResult.State expectedResult
    ) {
        executeAndAssert(
                StripeRefundEventContext.StripeRefundStatus.FAILED,
                initialOrderStatus,
                initialPaymentStatus,
                expectedPaymentStatus,
                expectedResult
        );
    }


    // =========================================================
    // CANCELED
    // =========================================================

    @ParameterizedTest(name = "[{index}] {0}/{1} -> {2}, result={3}")
    @MethodSource("failedAndCanceledCases")
    void shouldHandleCanceledRefundAccordingToStateMachine(
            OrderStatus initialOrderStatus,
            PaymentStatus initialPaymentStatus,
            PaymentStatus expectedPaymentStatus,
            StripeEventProcessingResult.State expectedResult
    ) {
        executeAndAssert(
                StripeRefundEventContext.StripeRefundStatus.CANCELED,
                initialOrderStatus,
                initialPaymentStatus,
                expectedPaymentStatus,
                expectedResult
        );
    }


    // =========================================================
    // REQUIRES ACTION
    // =========================================================

    @ParameterizedTest(name = "[{index}] {0}/{1} -> {2}, result={3}")
    @MethodSource("requiresActionCases")
    void shouldHandleRequiresActionRefundAccordingToStateMachine(
            OrderStatus initialOrderStatus,
            PaymentStatus initialPaymentStatus,
            PaymentStatus expectedPaymentStatus,
            StripeEventProcessingResult.State expectedResult
    ) {
        executeAndAssert(
                StripeRefundEventContext.StripeRefundStatus.REQUIRES_ACTION,
                initialOrderStatus,
                initialPaymentStatus,
                expectedPaymentStatus,
                expectedResult
        );
    }

    @ParameterizedTest(name = "CREATED/{0}: {1}/{2} -> {3}, result={4}")
    @MethodSource("createdCases")
    void shouldHandleCreatedRefundAccordingToStateMachine(
            StripeRefundEventContext.StripeRefundStatus stripeRefundStatus,
            OrderStatus initialOrderStatus,
            PaymentStatus initialPaymentStatus,
            PaymentStatus expectedPaymentStatus,
            StripeEventProcessingResult.State expectedResult
    ) {
        executeAndAssert(
                StripeWebhookEvent.REFUND_CREATED,
                stripeRefundStatus,
                initialOrderStatus,
                initialPaymentStatus,
                expectedPaymentStatus,
                expectedResult
        );
    }

    @ParameterizedTest(name = "FAILED: {0}/{1} -> {2}, result={3}")
    @MethodSource("failedEventCases")
    void shouldHandleFailedEventAccordingToStateMachine(
            OrderStatus initialOrderStatus,
            PaymentStatus initialPaymentStatus,
            PaymentStatus expectedPaymentStatus,
            StripeEventProcessingResult.State expectedResult
    ) {
        executeAndAssert(
                StripeWebhookEvent.REFUND_FAILED,
                StripeRefundEventContext.StripeRefundStatus.FAILED,
                initialOrderStatus,
                initialPaymentStatus,
                expectedPaymentStatus,
                expectedResult
        );
    }

    @ParameterizedTest(name = "FAILED event with status {0} is inconsistent")
    @MethodSource("invalidFailedEventStatuses")
    void shouldAcknowledgeFailedEventWithInvalidRefundStatus(
            StripeRefundEventContext.StripeRefundStatus stripeRefundStatus
    ) {
        executeAndAssert(
                StripeWebhookEvent.REFUND_FAILED,
                stripeRefundStatus,
                OrderStatus.EXPIRED,
                PaymentStatus.REFUND_PENDING,
                PaymentStatus.REFUND_PENDING,
                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
        );
    }


    // =========================================================
    // COMMON EXECUTION
    // =========================================================

    private void executeAndAssert(
            StripeRefundEventContext.StripeRefundStatus stripeRefundStatus,
            OrderStatus initialOrderStatus,
            PaymentStatus initialPaymentStatus,
            PaymentStatus expectedPaymentStatus,
            StripeEventProcessingResult.State expectedResult
    ) {
        executeAndAssert(StripeWebhookEvent.REFUND_UPDATED, stripeRefundStatus, initialOrderStatus,
                initialPaymentStatus, expectedPaymentStatus, expectedResult);
    }

    private void executeAndAssert(
            StripeWebhookEvent stripeWebhookEvent,
            StripeRefundEventContext.StripeRefundStatus stripeRefundStatus,
            OrderStatus initialOrderStatus,
            PaymentStatus initialPaymentStatus,
            PaymentStatus expectedPaymentStatus,
            StripeEventProcessingResult.State expectedResult
    ) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(initialOrderStatus);
        orderEntity.setPaymentStatus(initialPaymentStatus);

        String paymentIntentId = "pi_123";
        String refundId = "re_123";

        orderEntity.setStripePaymentIntentId(paymentIntentId);

        if (initialPaymentStatus == PaymentStatus.REFUND_PENDING
                || initialPaymentStatus == PaymentStatus.REFUND_FAILED
                || initialPaymentStatus == PaymentStatus.REFUNDED) {
            orderEntity.setStripeRefundId(refundId);
        }

        UUID orderId = UUID.randomUUID();

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId))
                .thenReturn(Optional.of(orderEntity));

        StripeRefundEventContext stripeRefundEventContext = new StripeRefundEventContext(
                orderId, paymentIntentId, stripeWebhookEvent, refundId, stripeRefundStatus
        );

        StripeEventProcessingResult result = switch (stripeWebhookEvent) {
            case REFUND_UPDATED -> stripeRefundEventHandlerTransactionService.handleUpdated(stripeRefundEventContext);
            case REFUND_CREATED -> stripeRefundEventHandlerTransactionService.handleCreated(stripeRefundEventContext);
            case REFUND_FAILED -> stripeRefundEventHandlerTransactionService.handleFailed(stripeRefundEventContext);
            default -> throw new IllegalArgumentException("Unexpected event: " + stripeWebhookEvent);
        };

        Assertions.assertThat(result.state())
                .isEqualTo(expectedResult);

        Assertions.assertThat(orderEntity.getOrderStatus())
                .isEqualTo(initialOrderStatus);

        Assertions.assertThat(orderEntity.getPaymentStatus())
                .isEqualTo(expectedPaymentStatus);

        Mockito.verify(paymentStateValidator)
                .validateRefund(orderEntity, paymentIntentId, refundId);
    }


    // =========================================================
    // METHOD SOURCES
    // =========================================================


    static Stream<Arguments> createdCases() {
        return Stream.of(StripeRefundEventContext.StripeRefundStatus.values())
                .flatMap(stripeRefundStatus -> {
                    Stream<Arguments> cases = switch (stripeRefundStatus) {
                        case SUCCEEDED -> succeedCases();
                        case PENDING -> pendingCases();
                        case FAILED, CANCELED -> failedAndCanceledCases();
                        case REQUIRES_ACTION -> requiresActionCases();
                    };
                    return cases.map(arguments -> {
                        Object[] values = arguments.get();
                        return Arguments.of(stripeRefundStatus, values[0], values[1], values[2], values[3]);
                    });
                });
    }

    static Stream<Arguments> failedEventCases() {
        return Stream.concat(
                nonExpiredCases(),
                Stream.of(
                        Arguments.of(OrderStatus.EXPIRED, PaymentStatus.PENDING, PaymentStatus.PENDING, StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT),
                        Arguments.of(OrderStatus.EXPIRED, PaymentStatus.CANCELED, PaymentStatus.CANCELED, StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT),
                        Arguments.of(OrderStatus.EXPIRED, PaymentStatus.SUCCEEDED, PaymentStatus.SUCCEEDED, StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT),
                        Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_PENDING, PaymentStatus.REFUND_FAILED, StripeEventProcessingResult.State.APPLIED),
                        Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_FAILED, PaymentStatus.REFUND_FAILED, StripeEventProcessingResult.State.IDEMPOTENT_NO_OP),
                        Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUNDED, PaymentStatus.REFUND_FAILED, StripeEventProcessingResult.State.APPLIED)
                )
        );
    }

    static Stream<Arguments> invalidFailedEventStatuses() {
        return Stream.of(
                StripeRefundEventContext.StripeRefundStatus.SUCCEEDED,
                StripeRefundEventContext.StripeRefundStatus.CANCELED,
                StripeRefundEventContext.StripeRefundStatus.REQUIRES_ACTION,
                StripeRefundEventContext.StripeRefundStatus.PENDING
        ).map(Arguments::of);
    }

    static Stream<Arguments> succeedCases() {
        return Stream.concat(
                nonExpiredCases(),
                Stream.of(
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.PENDING,
                                PaymentStatus.PENDING,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.CANCELED,
                                PaymentStatus.CANCELED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.SUCCEEDED,
                                PaymentStatus.SUCCEEDED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.REFUND_PENDING,
                                PaymentStatus.REFUNDED,
                                StripeEventProcessingResult.State.APPLIED
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.REFUND_FAILED,
                                PaymentStatus.REFUND_FAILED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.REFUNDED,
                                PaymentStatus.REFUNDED,
                                StripeEventProcessingResult.State.IDEMPOTENT_NO_OP
                        )
                )
        );
    }

    static Stream<Arguments> pendingCases() {
        return Stream.concat(
                nonExpiredCases(),
                Stream.of(
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.PENDING,
                                PaymentStatus.PENDING,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.CANCELED,
                                PaymentStatus.CANCELED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.SUCCEEDED,
                                PaymentStatus.SUCCEEDED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.REFUND_PENDING,
                                PaymentStatus.REFUND_PENDING,
                                StripeEventProcessingResult.State.IDEMPOTENT_NO_OP
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.REFUND_FAILED,
                                PaymentStatus.REFUND_FAILED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.REFUNDED,
                                PaymentStatus.REFUNDED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        )
                )
        );
    }

    static Stream<Arguments> failedAndCanceledCases() {
        return Stream.concat(
                nonExpiredCases(),
                Stream.of(
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.PENDING,
                                PaymentStatus.PENDING,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.CANCELED,
                                PaymentStatus.CANCELED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.SUCCEEDED,
                                PaymentStatus.SUCCEEDED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.REFUND_PENDING,
                                PaymentStatus.REFUND_FAILED,
                                StripeEventProcessingResult.State.APPLIED
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.REFUND_FAILED,
                                PaymentStatus.REFUND_FAILED,
                                StripeEventProcessingResult.State.IDEMPOTENT_NO_OP
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.REFUNDED,
                                PaymentStatus.REFUNDED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        )
                )
        );
    }

    static Stream<Arguments> requiresActionCases() {
        return Stream.concat(
                nonExpiredCases(),
                Stream.of(
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.PENDING,
                                PaymentStatus.PENDING,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.CANCELED,
                                PaymentStatus.CANCELED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.SUCCEEDED,
                                PaymentStatus.SUCCEEDED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.REFUND_PENDING,
                                PaymentStatus.REFUND_PENDING,
                                StripeEventProcessingResult.State.IDEMPOTENT_NO_OP
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.REFUND_FAILED,
                                PaymentStatus.REFUND_FAILED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        ),
                        Arguments.of(
                                OrderStatus.EXPIRED,
                                PaymentStatus.REFUNDED,
                                PaymentStatus.REFUNDED,
                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                        )
                )
        );
    }

    private static Stream<Arguments> nonExpiredCases() {
        return Stream.of(
                        OrderStatus.PENDING,
                        OrderStatus.CANCELED,
                        OrderStatus.COMPLETED
                )
                .flatMap(orderStatus ->
                        Stream.of(
                                        PaymentStatus.PENDING,
                                        PaymentStatus.CANCELED,
                                        PaymentStatus.SUCCEEDED,
                                        PaymentStatus.REFUND_PENDING,
                                        PaymentStatus.REFUND_FAILED,
                                        PaymentStatus.REFUNDED
                                )
                                .map(paymentStatus ->
                                        Arguments.of(
                                                orderStatus,
                                                paymentStatus,
                                                paymentStatus,
                                                StripeEventProcessingResult.State.ACKNOWLEDGED_INCONSISTENT
                                        )
                                )
                );
    }


    // =========================================================
    // ADDITIONAL BEHAVIOR
    // =========================================================

    @ParameterizedTest(name = "{0} sets refundedAt after success")
    @EnumSource(value = StripeWebhookEvent.class, names = {"REFUND_CREATED", "REFUND_UPDATED"})
    void shouldSetRefundedAtWhenRefundSucceeded(StripeWebhookEvent stripeWebhookEvent) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.EXPIRED);
        orderEntity.setPaymentStatus(PaymentStatus.REFUND_PENDING);
        orderEntity.setStripePaymentIntentId("pi_123");
        orderEntity.setStripeRefundId("re_123");

        UUID orderId = UUID.randomUUID();

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId))
                .thenReturn(Optional.of(orderEntity));

        StripeRefundEventContext stripeRefundEventContext = new StripeRefundEventContext(
                orderId, "pi_123", stripeWebhookEvent, "re_123",
                StripeRefundEventContext.StripeRefundStatus.SUCCEEDED
        );

        if (stripeWebhookEvent == StripeWebhookEvent.REFUND_CREATED)
            stripeRefundEventHandlerTransactionService.handleCreated(stripeRefundEventContext);
        else
            stripeRefundEventHandlerTransactionService.handleUpdated(stripeRefundEventContext);

        Assertions.assertThat(orderEntity.getRefundedAt())
                .isNotNull();
    }

    @Test
    void shouldClearRefundedAtWhenFailedEventArrivesAfterSucceededRefund() {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.EXPIRED);
        orderEntity.setPaymentStatus(PaymentStatus.REFUNDED);
        orderEntity.setStripePaymentIntentId("pi_123");
        orderEntity.setStripeRefundId("re_123");
        orderEntity.setRefundedAt(Instant.now());

        UUID orderId = UUID.randomUUID();

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId))
                .thenReturn(Optional.of(orderEntity));

        StripeEventProcessingResult result = stripeRefundEventHandlerTransactionService.handleFailed(
                new StripeRefundEventContext(orderId, "pi_123", StripeWebhookEvent.REFUND_FAILED,
                        "re_123", StripeRefundEventContext.StripeRefundStatus.FAILED)
        );

        Assertions.assertThat(result.state()).isEqualTo(StripeEventProcessingResult.State.APPLIED);
        Assertions.assertThat(orderEntity.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_FAILED);
        Assertions.assertThat(orderEntity.getRefundedAt()).isNull();
        Mockito.verify(paymentStateValidator).validateRefund(orderEntity, "pi_123", "re_123");
    }

    @Test
    void shouldThrowWhenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId))
                .thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() ->
                        stripeRefundEventHandlerTransactionService.handleUpdated(
                                new StripeRefundEventContext(
                                        orderId,
                                        "pi_123",
                                        StripeWebhookEvent.REFUND_UPDATED,
                                        "re_123",
                                        StripeRefundEventContext.StripeRefundStatus.SUCCEEDED
                                )
                        ))
                .isInstanceOf(NoSuchDbRecordException.class);

        Mockito.verifyNoInteractions(paymentStateValidator);
    }
}

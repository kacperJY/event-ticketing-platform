package pl.kacper.sales_api.domain.order.stripe.refund;

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
import pl.kacper.sales_api.common.exception.paymentException.RefundStateMismatchException;
import pl.kacper.sales_api.domain.order.OrderEntity;
import pl.kacper.sales_api.domain.order.OrderRepository;
import pl.kacper.sales_api.domain.order.OrderStatus;
import pl.kacper.sales_api.domain.order.PaymentStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
class RefundInitializerTransactionServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private RefundInitializerTransactionService refundInitializerTransactionService;

    @Test
    void shouldSuccessfullyModifyOrder() {
        UUID orderId = UUID.randomUUID();
        String incomingRefundId = "re_incoming123";
        String stripePaymentIntentId = "pi_123";
        OrderStatus orderStatusBefore = OrderStatus.EXPIRED;
        PaymentStatus paymentStatusBefore = PaymentStatus.REFUND_REQUIRED;

        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(orderStatusBefore);
        orderEntity.setPaymentStatus(paymentStatusBefore);
        orderEntity.setStripePaymentIntentId(stripePaymentIntentId);

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId)).thenReturn(Optional.of(orderEntity));

        refundInitializerTransactionService.validateAndUpdateAfterRefundCreate(orderId, incomingRefundId);

        Assertions.assertThat(orderEntity.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        Assertions.assertThat(orderEntity.getOrderStatus()).isEqualTo(orderStatusBefore);
        Assertions.assertThat(orderEntity.getStripeRefundId()).isEqualTo(incomingRefundId);
        Assertions.assertThat(orderEntity.getRefundRequestedAt()).isNotNull();
        Assertions.assertThat(orderEntity.getStripePaymentIntentId()).isEqualTo(stripePaymentIntentId);
    }

    private static Stream<Arguments> createArgumentForNoOperationState() {
        return Stream.of(
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUNDED,
                        Instant.now().plus(Duration.ofMinutes(30)),
                        Instant.now(),
                        "re_123",
                        "re_123",
                        "pi_123"
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_FAILED,
                        null,
                        Instant.now(),
                        "re_123",
                        "re_123",
                        "pi_123"
                ),
                Arguments.of(
                        OrderStatus.EXPIRED,
                        PaymentStatus.REFUND_PENDING,
                        null,
                        Instant.now(),
                        "re_123",
                        "re_123",
                        "pi_123"
                )
        );
    }

    private OrderEntity buildOrderEntity(OrderStatus orderStatusOriginal,
                                         PaymentStatus paymentStatusOriginal,
                                         Instant refundedAtOriginal,
                                         Instant refundRequestedAtOriginal,
                                         String stripeRefundIdOriginal,
                                         String paymentIntentIdOriginal) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(orderStatusOriginal);
        orderEntity.setPaymentStatus(paymentStatusOriginal);
        orderEntity.setRefundedAt(refundedAtOriginal);
        orderEntity.setRefundRequestedAt(refundRequestedAtOriginal);
        orderEntity.setStripePaymentIntentId(paymentIntentIdOriginal);
        orderEntity.setStripeRefundId(stripeRefundIdOriginal);
        return orderEntity;
    }

    @ParameterizedTest
    @MethodSource("createArgumentForNoOperationState")
    void shouldDoNothingForNoOperationState(
            OrderStatus orderStatusOriginal,
            PaymentStatus paymentStatusOriginal,
            Instant refundedAtOriginal,
            Instant refundRequestedAtOriginal,
            String stripeRefundIdOriginal,
            String incomingRefundId,
            String paymentIntentIdOriginal
    ) {
        UUID orderId = UUID.randomUUID();

        OrderEntity orderEntity = buildOrderEntity(
                orderStatusOriginal,
                paymentStatusOriginal,
                refundedAtOriginal,
                refundRequestedAtOriginal,
                stripeRefundIdOriginal,
                paymentIntentIdOriginal
        );

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId)).thenReturn(Optional.of(orderEntity));

        refundInitializerTransactionService.validateAndUpdateAfterRefundCreate(orderId,incomingRefundId);

        Assertions.assertThat(orderEntity.getPaymentStatus()).isEqualTo(paymentStatusOriginal);
        Assertions.assertThat(orderEntity.getOrderStatus()).isEqualTo(orderStatusOriginal);
        Assertions.assertThat(orderEntity.getStripeRefundId()).isEqualTo(incomingRefundId);
        Assertions.assertThat(orderEntity.getRefundRequestedAt()).isEqualTo(refundRequestedAtOriginal);
        Assertions.assertThat(orderEntity.getRefundedAt()).isEqualTo(refundedAtOriginal);
        Assertions.assertThat(orderEntity.getStripePaymentIntentId()).isEqualTo(paymentIntentIdOriginal);
    }

    private static Stream<Arguments> createArgumentForInvalidState() {
        Instant refundedAt = Instant.now().plus(Duration.ofMinutes(30));
        Instant refundRequestedAt = Instant.now();

        return Stream.of(
                Arguments.of(OrderStatus.PENDING, PaymentStatus.REFUND_REQUIRED, null, null, null, "re_123", "pi_123"),
                Arguments.of(OrderStatus.CANCELED, PaymentStatus.REFUND_REQUIRED, null, null, null, "re_123", "pi_123"),
                Arguments.of(OrderStatus.COMPLETED, PaymentStatus.REFUND_REQUIRED, null, null, null, "re_123", "pi_123"),

                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_REQUIRED, refundedAt, null, null, "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_REQUIRED, null, refundRequestedAt, null, "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_REQUIRED, null, null, "re_123", "re_123", "pi_123"),

                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.NOT_INITIALIZED, null, null, null, "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.PENDING, null, null, null, "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.SUCCEEDED, null, null, null, "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.CANCELED, null, null, null, "re_123", "pi_123"),

                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_PENDING, refundedAt, refundRequestedAt, "re_123", "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_PENDING, null, null, "re_123", "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_PENDING, null, refundRequestedAt, null, "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_PENDING, null, refundRequestedAt, "re_123", "re_other", "pi_123"),

                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUNDED, null, refundRequestedAt, "re_123", "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUNDED, refundedAt, null, "re_123", "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUNDED, refundedAt, refundRequestedAt, null, "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUNDED, refundedAt, refundRequestedAt, "re_123", "re_other", "pi_123"),

                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_FAILED, refundedAt, refundRequestedAt, "re_123", "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_FAILED, null, null, "re_123", "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_FAILED, null, refundRequestedAt, null, "re_123", "pi_123"),
                Arguments.of(OrderStatus.EXPIRED, PaymentStatus.REFUND_FAILED, null, refundRequestedAt, "re_123", "re_other", "pi_123"),

                Arguments.of(OrderStatus.COMPLETED, PaymentStatus.REFUND_PENDING, null, refundRequestedAt, "re_123", "re_123", "pi_123"),
                Arguments.of(OrderStatus.COMPLETED, PaymentStatus.REFUNDED, refundedAt, refundRequestedAt, "re_123", "re_123", "pi_123"),
                Arguments.of(OrderStatus.COMPLETED, PaymentStatus.REFUND_FAILED, null, refundRequestedAt, "re_123", "re_123", "pi_123")
        );
    }

    @ParameterizedTest
    @MethodSource("createArgumentForInvalidState")
    void shouldRejectInvalidStateWithoutModifyingOrder(
            OrderStatus orderStatusOriginal,
            PaymentStatus paymentStatusOriginal,
            Instant refundedAtOriginal,
            Instant refundRequestedAtOriginal,
            String stripeRefundIdOriginal,
            String incomingRefundId,
            String paymentIntentIdOriginal
    ) {
        UUID orderId = UUID.randomUUID();

        OrderEntity orderEntity = buildOrderEntity(
                orderStatusOriginal,
                paymentStatusOriginal,
                refundedAtOriginal,
                refundRequestedAtOriginal,
                stripeRefundIdOriginal,
                paymentIntentIdOriginal
        );

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId)).thenReturn(Optional.of(orderEntity));

        Assertions.assertThatThrownBy(() -> refundInitializerTransactionService.validateAndUpdateAfterRefundCreate(orderId, incomingRefundId))
                .isInstanceOfSatisfying(RefundStateMismatchException.class,
                        exception -> Assertions.assertThat(exception.getMismatchReason())
                                .isEqualTo(RefundStateMismatchException.RefundMismatchReason.INVALID_LOCAL_REFUND_STATE));

        Assertions.assertThat(orderEntity.getOrderStatus()).isEqualTo(orderStatusOriginal);
        Assertions.assertThat(orderEntity.getPaymentStatus()).isEqualTo(paymentStatusOriginal);
        Assertions.assertThat(orderEntity.getRefundedAt()).isEqualTo(refundedAtOriginal);
        Assertions.assertThat(orderEntity.getRefundRequestedAt()).isEqualTo(refundRequestedAtOriginal);
        Assertions.assertThat(orderEntity.getStripeRefundId()).isEqualTo(stripeRefundIdOriginal);
        Assertions.assertThat(orderEntity.getStripePaymentIntentId()).isEqualTo(paymentIntentIdOriginal);
    }

    @Test
    void shouldThrowWhenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();
        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId)).thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() -> refundInitializerTransactionService.validateAndUpdateAfterRefundCreate(orderId, "re_123"))
                .isInstanceOf(NoSuchDbRecordException.class);
    }
}

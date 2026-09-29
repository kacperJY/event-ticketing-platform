package pl.kacper.sales_api.domain.order.stripe;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pl.kacper.sales_api.common.exception.paymentException.PaymentIntentStateMismatchException;
import pl.kacper.sales_api.common.exception.paymentException.RefundStateMismatchException;
import pl.kacper.sales_api.domain.order.OrderEntity;
import pl.kacper.sales_api.domain.order.PaymentStatus;

import java.util.stream.Stream;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class PaymentStateValidatorTest {

    private final PaymentStateValidator paymentStateValidator = new PaymentStateValidator();

    static Stream<Arguments> paymentIntentValidationFailureCases() {
        return Stream.of(
                Arguments.of(PaymentStatus.NOT_INITIALIZED, null, "pi_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.LOCAL_PAYMENT_INTENT_MISSING),
                Arguments.of(PaymentStatus.PENDING, "pi_123", "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.REFUND_REQUIRED, "pi_123", "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.CANCELED, "pi_123", "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.SUCCEEDED, "pi_123", "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.REFUNDED, "pi_123", "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.REFUND_PENDING, "pi_123", "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.REFUND_FAILED, "pi_123", "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.NOT_INITIALIZED, "pi_123", "pi_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.NOT_INITIALIZED, "pi_123", "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.PENDING, null, "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.REFUND_REQUIRED, null, "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.CANCELED, null, "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.SUCCEEDED, null, "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.REFUNDED, null, "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.REFUND_PENDING, null, "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.REFUND_FAILED, null, "pi_321", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE)
        );
    }

    @ParameterizedTest(name = "[{index}] paymentStatus={0}, localPI={1}, incomingPI={2} -> {3}")
    @MethodSource("paymentIntentValidationFailureCases")
    void shouldThrowExpectedMismatchReasonWhenPaymentIntentValidationFails(
            PaymentStatus paymentStatus,
            String orderPaymentId,
            String incomingPaymentId,
            PaymentIntentStateMismatchException.PaymentIntentMismatchReason paymentIntentMismatchReason
    ) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setPaymentStatus(paymentStatus);
        orderEntity.setStripePaymentIntentId(orderPaymentId);

        PaymentIntentStateMismatchException mismatchException = Assertions.catchThrowableOfType(PaymentIntentStateMismatchException.class, () -> paymentStateValidator.validatePaymentIntent(orderEntity, incomingPaymentId));
        Assertions.assertThat(mismatchException.getMismatchReason()).isEqualTo(paymentIntentMismatchReason);
    }

    static Stream<Arguments> paymentIntentValidationSuccessCases() {
        return Stream.of(
                Arguments.of(PaymentStatus.PENDING, "pi_123", "pi_123"),
                Arguments.of(PaymentStatus.SUCCEEDED, "pi_123", "pi_123"),
                Arguments.of(PaymentStatus.CANCELED, "pi_123", "pi_123"),
                Arguments.of(PaymentStatus.REFUNDED, "pi_123", "pi_123"),
                Arguments.of(PaymentStatus.REFUND_REQUIRED, "pi_123", "pi_123"),
                Arguments.of(PaymentStatus.REFUND_PENDING, "pi_123", "pi_123"),
                Arguments.of(PaymentStatus.REFUND_FAILED, "pi_123", "pi_123")
        );
    }

    @ParameterizedTest(name = "[{index}] paymentStatus={0}, localPI={1}, incomingPI={2}")
    @MethodSource("paymentIntentValidationSuccessCases")
    void shouldValidatePaymentIntentSuccessfullyForValidState(
            PaymentStatus paymentStatus,
            String orderPaymentId,
            String incomingPaymentId
    ) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setPaymentStatus(paymentStatus);
        orderEntity.setStripePaymentIntentId(orderPaymentId);

        paymentStateValidator.validatePaymentIntent(orderEntity, incomingPaymentId);
    }


    // REFUND VALIDATION

    static Stream<Arguments> paymentIntentFailureCasesForRefundValidation() {
        return Stream.of(
                Arguments.of(PaymentStatus.NOT_INITIALIZED, null, "re_123", "pi_123", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.LOCAL_PAYMENT_INTENT_MISSING),
                Arguments.of(PaymentStatus.PENDING, "pi_123", "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.REFUND_REQUIRED, "pi_123", "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.CANCELED, "pi_123", "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.SUCCEEDED, "pi_123", "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.REFUNDED, "pi_123", "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.REFUND_PENDING, "pi_123", "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.REFUND_FAILED, "pi_123", "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.PAYMENT_INTENT_ID_MISMATCH),
                Arguments.of(PaymentStatus.NOT_INITIALIZED, "pi_123", "re_123", "pi_123", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.NOT_INITIALIZED, "pi_123", "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.PENDING, null, "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.REFUND_REQUIRED, null, "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.CANCELED, null, "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.SUCCEEDED, null, "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.REFUNDED, null, "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.REFUND_PENDING, null, "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE),
                Arguments.of(PaymentStatus.REFUND_FAILED, null, "re_123", "pi_321", "re_123", PaymentIntentStateMismatchException.PaymentIntentMismatchReason.INVALID_LOCAL_PAYMENT_STATE)
        );
    }

    @ParameterizedTest(name = "[{index}] paymentStatus={0}, localPI={1}, localRI={2}, incomingPI={3}, incomingRI={4}")
    @MethodSource("paymentIntentFailureCasesForRefundValidation")
    void shouldThrowExpectedMismatchReasonWhenRefundValidationForPaymentIntentFails(
            PaymentStatus paymentStatus,
            String orderPaymentId,
            String orderRefundId,
            String incomingPaymentId,
            String incomingRefundId,
            PaymentIntentStateMismatchException.PaymentIntentMismatchReason paymentIntentMismatchReason
    ) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setPaymentStatus(paymentStatus);
        orderEntity.setStripePaymentIntentId(orderPaymentId);
        orderEntity.setStripeRefundId(orderRefundId);

        PaymentIntentStateMismatchException mismatchException = Assertions.catchThrowableOfType(PaymentIntentStateMismatchException.class, () -> paymentStateValidator.validateRefund(orderEntity, incomingPaymentId, incomingRefundId));
        Assertions.assertThat(mismatchException.getMismatchReason()).isEqualTo(paymentIntentMismatchReason);
    }


    static Stream<Arguments> refundValidationSuccessCases() {
        return Stream.of(
                Arguments.of(PaymentStatus.REFUND_PENDING, "pi_123", "re_123", "pi_123", "re_123"),
                Arguments.of(PaymentStatus.REFUND_FAILED, "pi_123", "re_123", "pi_123", "re_123"),
                Arguments.of(PaymentStatus.REFUNDED, "pi_123", "re_123", "pi_123", "re_123")
        );
    }

    @ParameterizedTest(name = "[{index}] paymentStatus={0}, localPI={1}, localRI={2}, incomingPI={3}, incomingRI={4}")
    @MethodSource("refundValidationSuccessCases")
    void shouldValidateRefundSuccessfullyForValidState(
            PaymentStatus paymentStatus,
            String orderPaymentId,
            String orderRefundId,
            String incomingPaymentId,
            String incomingRefundId
    ) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setPaymentStatus(paymentStatus);
        orderEntity.setStripePaymentIntentId(orderPaymentId);
        orderEntity.setStripeRefundId(orderRefundId);

        paymentStateValidator.validateRefund(orderEntity, incomingPaymentId, incomingRefundId);
    }


    static Stream<Arguments> paymentIntentSuccessCasesAndRefundFailureCasesForRefundValidation() {
        return Stream.of(
                Arguments.of(PaymentStatus.PENDING, "pi_123", "re_123", "pi_123", "re_123", RefundStateMismatchException.RefundMismatchReason.INVALID_LOCAL_REFUND_STATE),
                Arguments.of(PaymentStatus.SUCCEEDED, "pi_123", "re_123", "pi_123", "re_123", RefundStateMismatchException.RefundMismatchReason.INVALID_LOCAL_REFUND_STATE),
                Arguments.of(PaymentStatus.CANCELED, "pi_123", "re_123", "pi_123", "re_123", RefundStateMismatchException.RefundMismatchReason.INVALID_LOCAL_REFUND_STATE),

                Arguments.of(PaymentStatus.REFUND_REQUIRED, "pi_123", null, "pi_123", "re_123", RefundStateMismatchException.RefundMismatchReason.LOCAL_REFUND_ID_MISSING),
                Arguments.of(PaymentStatus.REFUND_REQUIRED, "pi_123", "re_123", "pi_123", "re_123", RefundStateMismatchException.RefundMismatchReason.INVALID_LOCAL_REFUND_STATE),

                Arguments.of(PaymentStatus.REFUNDED, "pi_123", null, "pi_123", "re_123", RefundStateMismatchException.RefundMismatchReason.INVALID_LOCAL_REFUND_STATE),
                Arguments.of(PaymentStatus.REFUNDED, "pi_123", "re_123", "pi_123", "re_321", RefundStateMismatchException.RefundMismatchReason.REFUND_ID_MISMATCH),

                Arguments.of(PaymentStatus.REFUND_PENDING, "pi_123", null, "pi_123", "re_123", RefundStateMismatchException.RefundMismatchReason.INVALID_LOCAL_REFUND_STATE),
                Arguments.of(PaymentStatus.REFUND_PENDING, "pi_123", "re_123", "pi_123", "re_321", RefundStateMismatchException.RefundMismatchReason.REFUND_ID_MISMATCH),

                Arguments.of(PaymentStatus.REFUND_FAILED, "pi_123", null, "pi_123", "re_123", RefundStateMismatchException.RefundMismatchReason.INVALID_LOCAL_REFUND_STATE),
                Arguments.of(PaymentStatus.REFUND_FAILED, "pi_123", "re_123", "pi_123", "re_321", RefundStateMismatchException.RefundMismatchReason.REFUND_ID_MISMATCH)
        );
    }

    @ParameterizedTest(name = "[{index}] paymentStatus={0}, localPI={1}, localRI={2}, incomingPI={3}, incomingRI={4} -> {5}")
    @MethodSource("paymentIntentSuccessCasesAndRefundFailureCasesForRefundValidation")
    void shouldThrowExpectedMismatchReasonForSuccessfullyPaymentIntentValidationAndRefundValidationFails(
            PaymentStatus paymentStatus,
            String orderPaymentId,
            String orderRefundId,
            String incomingPaymentId,
            String incomingRefundId,
            RefundStateMismatchException.RefundMismatchReason refundMismatchReason
    ) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setPaymentStatus(paymentStatus);
        orderEntity.setStripePaymentIntentId(orderPaymentId);
        orderEntity.setStripeRefundId(orderRefundId);

        RefundStateMismatchException mismatchException = Assertions.catchThrowableOfType(
                RefundStateMismatchException.class,
                () -> paymentStateValidator.validateRefund(orderEntity, incomingPaymentId, incomingRefundId));
        Assertions.assertThat(mismatchException.getMismatchReason()).isEqualTo(refundMismatchReason);
    }
}

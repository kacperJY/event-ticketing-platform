package pl.kacper.sales_api.common.exception.paymentException;

public class PaymentIntentStateMismatchException extends RuntimeException{
    private final PaymentIntentMismatchReason paymentIntentMismatchReason;

    public PaymentIntentStateMismatchException(PaymentIntentMismatchReason paymentIntentMismatchReason, String message) {
        super(message);
        this.paymentIntentMismatchReason = paymentIntentMismatchReason;
    }

    public PaymentIntentStateMismatchException(PaymentIntentMismatchReason paymentIntentMismatchReason, String message, Throwable cause) {
        super(message, cause);
        this.paymentIntentMismatchReason = paymentIntentMismatchReason;
    }


    public enum PaymentIntentMismatchReason {
        PAYMENT_INTENT_ID_MISMATCH,
        LOCAL_PAYMENT_INTENT_MISSING,
        INVALID_LOCAL_PAYMENT_STATE,
    }

    public PaymentIntentMismatchReason getMismatchReason() {
        return paymentIntentMismatchReason;
    }
}

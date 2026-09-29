package pl.kacper.sales_api.common.exception.paymentException;

public class RefundStateMismatchException extends RuntimeException {

    private final RefundMismatchReason refundMismatchReason;

    public RefundStateMismatchException(RefundMismatchReason refundMismatchReason, String message) {
        super(message);
        this.refundMismatchReason = refundMismatchReason;
    }

    public RefundStateMismatchException(RefundMismatchReason refundMismatchReason, String message, Throwable cause) {
        super(message, cause);
        this.refundMismatchReason = refundMismatchReason;
    }

    public enum RefundMismatchReason {
        REFUND_ID_MISMATCH,
        LOCAL_REFUND_ID_MISSING,
        INVALID_LOCAL_REFUND_STATE,
    }

    public RefundMismatchReason getMismatchReason() {
        return refundMismatchReason;
    }
}

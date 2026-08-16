package pl.kacper.sales_api.common.exception.paymentException;

public class PaymentIntentStateMismatchException extends RuntimeException{
    public PaymentIntentStateMismatchException(String message) {
        super(message);
    }

    public PaymentIntentStateMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}

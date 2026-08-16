package pl.kacper.sales_api.common.exception.paymentException;

public class PaymentInconsistentStateException extends RuntimeException{

    public PaymentInconsistentStateException(String message) {
        super(message);
    }

    public PaymentInconsistentStateException(String message, Throwable cause) {
        super(message, cause);
    }
}

package pl.kacper.sales_api.common.exception.paymentException;

public class PaymentProcessingException extends RuntimeException{

    public PaymentProcessingException(String message) {
        super(message);
    }

    public PaymentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}

package pl.kacper.sales_api.common.exception.paymentException;

public class InitializationPaymentException extends RuntimeException{

    public InitializationPaymentException(String message) {
        super(message);
    }

    public InitializationPaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}

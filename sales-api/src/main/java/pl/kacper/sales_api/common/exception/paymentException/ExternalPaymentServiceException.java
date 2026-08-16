package pl.kacper.sales_api.common.exception.paymentException;

public class ExternalPaymentServiceException extends RuntimeException{
    public ExternalPaymentServiceException(String message) {
        super(message);
    }

    public ExternalPaymentServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}

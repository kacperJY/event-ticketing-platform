package pl.kacper.sales_api.common.exception.stripe;

public class HandleEventContextException extends RuntimeException{

    public HandleEventContextException(String message) {
        super(message);
    }

    public HandleEventContextException(String message, Throwable cause) {
        super(message, cause);
    }
}

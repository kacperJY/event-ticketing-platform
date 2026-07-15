package pl.kacper.sales_api.common.exception;

public class NoSuchQuantityException extends RuntimeException{

    public NoSuchQuantityException(String message) {
        super(message);
    }

    public NoSuchQuantityException(String message, Throwable cause) {
        super(message, cause);
    }
}

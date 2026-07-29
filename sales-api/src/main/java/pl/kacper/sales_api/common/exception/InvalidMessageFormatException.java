package pl.kacper.sales_api.common.exception;

public class InvalidMessageFormatException extends RuntimeException{
    public InvalidMessageFormatException(String message) {
        super(message);
    }

    public InvalidMessageFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}

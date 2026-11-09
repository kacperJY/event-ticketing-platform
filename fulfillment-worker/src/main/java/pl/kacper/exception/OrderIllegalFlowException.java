package pl.kacper.exception;

public class OrderIllegalFlowException extends RuntimeException{

    public OrderIllegalFlowException(String message) {
        super(message);
    }

    public OrderIllegalFlowException(String message, Throwable cause) {
        super(message, cause);
    }
}

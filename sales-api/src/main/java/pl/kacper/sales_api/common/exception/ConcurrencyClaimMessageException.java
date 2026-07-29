package pl.kacper.sales_api.common.exception;

public class ConcurrencyClaimMessageException extends RuntimeException{

    public ConcurrencyClaimMessageException(String message) {
        super(message);
    }

    public ConcurrencyClaimMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}

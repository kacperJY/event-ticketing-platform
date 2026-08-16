package pl.kacper.sales_api.common.exception;

public class ConcurrencyClaimException extends RuntimeException{

    public ConcurrencyClaimException(String message) {
        super(message);
    }

    public ConcurrencyClaimException(String message, Throwable cause) {
        super(message, cause);
    }
}

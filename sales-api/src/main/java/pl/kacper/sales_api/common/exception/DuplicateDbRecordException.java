package pl.kacper.sales_api.common.exception;

public class DuplicateDbRecordException extends RuntimeException{

    public DuplicateDbRecordException(String message) {
        super(message);
    }

    public DuplicateDbRecordException(String message, Throwable cause) {
        super(message, cause);
    }
}

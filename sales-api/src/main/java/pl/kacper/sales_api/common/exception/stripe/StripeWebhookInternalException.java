package pl.kacper.sales_api.common.exception.stripe;

public class StripeWebhookInternalException extends RuntimeException{

    public StripeWebhookInternalException(String message) {
        super(message);
    }

    public StripeWebhookInternalException(String message, Throwable cause) {
        super(message, cause);
    }
}

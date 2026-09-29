package pl.kacper.sales_api.common.exception.stripe;

public class StripeWebhookException extends RuntimeException{

    public StripeWebhookException(String message) {
        super(message);
    }

    public StripeWebhookException(String message, Throwable cause) {
        super(message, cause);
    }
}

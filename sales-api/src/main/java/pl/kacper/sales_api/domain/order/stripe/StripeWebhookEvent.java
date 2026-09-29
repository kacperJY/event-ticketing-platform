package pl.kacper.sales_api.domain.order.stripe;

public enum StripeWebhookEvent {

    PI_SUCCEEDED("payment_intent.succeeded"),
    PI_CANCELED("payment_intent.canceled"),
    PI_FAILED("payment_intent.payment_failed"),
    REFUND_UPDATED("refund.updated"),
    REFUND_CREATED("refund.created"),
    REFUND_FAILED("refund.failed");

    private final String value;

    private StripeWebhookEvent(String value){
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}

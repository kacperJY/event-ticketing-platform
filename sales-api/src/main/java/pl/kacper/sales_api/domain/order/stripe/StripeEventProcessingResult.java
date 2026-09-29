package pl.kacper.sales_api.domain.order.stripe;

public record StripeEventProcessingResult(
        State state,
        String message
) {


    enum State {
        APPLIED,
        IDEMPOTENT_NO_OP,
        ACKNOWLEDGED_INCONSISTENT
    }
}

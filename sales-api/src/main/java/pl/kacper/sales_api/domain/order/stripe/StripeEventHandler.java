package pl.kacper.sales_api.domain.order.stripe;

public interface StripeEventHandler {

    StripeEventProcessingResult handle(StripeEventContext stripeEventContext);
}

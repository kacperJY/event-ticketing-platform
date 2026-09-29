package pl.kacper.sales_api.domain.order.stripe;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor
public sealed class StripeEventContext permits StripeRefundEventContext {
    private final UUID orderId;
    private final String paymentIntentId;
    private final StripeWebhookEvent stripeWebhookEvent;
}

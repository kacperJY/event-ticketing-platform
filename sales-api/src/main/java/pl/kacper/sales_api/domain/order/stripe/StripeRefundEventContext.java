package pl.kacper.sales_api.domain.order.stripe;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Optional;
import java.util.UUID;

@Getter
final class StripeRefundEventContext extends StripeEventContext {
    private final String stripeRefundId;
    private final StripeRefundStatus stripeRefundStatus;

    StripeRefundEventContext(UUID orderId, String paymentIntentId,StripeWebhookEvent stripeWebhookEvent, String stripeRefundId, StripeRefundStatus stripeRefundStatus){
        super(orderId,paymentIntentId,stripeWebhookEvent);
        this.stripeRefundId = stripeRefundId;
        this.stripeRefundStatus = stripeRefundStatus;
    }

    enum StripeRefundStatus {
        SUCCEEDED("succeeded"),
        FAILED("failed"),
        CANCELED("canceled"),
        REQUIRES_ACTION("requires_action"),
        PENDING("pending");

        private final String value;

        StripeRefundStatus(String value) {
            this.value = value;
        }

        static Optional<StripeRefundStatus> fromValue(String value){
            for (StripeRefundStatus stripeRefundStatus : StripeRefundStatus.values()) {
                if(stripeRefundStatus.value.equals(value)) return Optional.of(stripeRefundStatus);
            }
            return Optional.empty();
        }
    }
}

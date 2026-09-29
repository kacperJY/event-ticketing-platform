package pl.kacper.sales_api.domain.order.stripe;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.common.exception.stripe.HandleEventContextException;

import java.util.Optional;

@Component
public class StripeEventHandlerDispatcher implements StripeEventHandler {

    private final StripePaymentIntentEventHandlerTransactionService stripePaymentIntentEventHandlerTransactionService;
    private final StripeRefundEventHandlerTransactionService stripeRefundEventHandlerTransactionService;

    @Autowired
    public StripeEventHandlerDispatcher(StripePaymentIntentEventHandlerTransactionService stripePaymentIntentEventHandlerTransactionService, StripeRefundEventHandlerTransactionService stripeRefundEventHandlerTransactionService) {
        this.stripePaymentIntentEventHandlerTransactionService = stripePaymentIntentEventHandlerTransactionService;
        this.stripeRefundEventHandlerTransactionService = stripeRefundEventHandlerTransactionService;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StripeEventProcessingResult handle(StripeEventContext stripeEventContext) {
        StripeWebhookEvent stripeWebhookEvent = stripeEventContext.getStripeWebhookEvent();
        return switch (stripeWebhookEvent) {
            case PI_SUCCEEDED -> stripePaymentIntentEventHandlerTransactionService.handleSucceed(stripeEventContext);
            case PI_CANCELED -> stripePaymentIntentEventHandlerTransactionService.handleCanceled(stripeEventContext);
            case PI_FAILED ->
                    stripePaymentIntentEventHandlerTransactionService.validatePaymentIntentIdForFailedEvent(stripeEventContext);
            case REFUND_UPDATED -> {
                if (stripeEventContext instanceof StripeRefundEventContext stripeRefundEventContext)
                    yield stripeRefundEventHandlerTransactionService.handleUpdated(stripeRefundEventContext);
                else
                    throw new HandleEventContextException("Invalid type of StripeEventContext for Event: " + stripeWebhookEvent);
            }
            case REFUND_CREATED -> {
                if (stripeEventContext instanceof StripeRefundEventContext stripeRefundEventContext)
                    yield stripeRefundEventHandlerTransactionService.handleCreated(stripeRefundEventContext);
                else
                    throw new HandleEventContextException("Invalid type of StripeEventContext for Event: " + stripeWebhookEvent);
            }
            case REFUND_FAILED -> {
                if (stripeEventContext instanceof StripeRefundEventContext stripeRefundEventContext)
                    yield stripeRefundEventHandlerTransactionService.handleFailed(stripeRefundEventContext);
                else
                    throw new HandleEventContextException("Invalid type of StripeEventContext for Event: " + stripeWebhookEvent);
            }
        };
    }

    public static Optional<StripeWebhookEvent> fromValue(String source) {
        for (StripeWebhookEvent stripeWebhookEvent : StripeWebhookEvent.values()) {
            if (stripeWebhookEvent.getValue().equals(source)) return Optional.of(stripeWebhookEvent);
        }
        return Optional.empty();
    }

}

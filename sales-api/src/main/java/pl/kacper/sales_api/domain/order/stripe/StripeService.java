package pl.kacper.sales_api.domain.order.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.common.exception.paymentException.RefundStateMismatchException;
import pl.kacper.sales_api.common.exception.stripe.StripeWebhookException;
import pl.kacper.sales_api.common.exception.stripe.StripeWebhookInternalException;
import pl.kacper.sales_api.common.exception.paymentException.PaymentIntentStateMismatchException;
import pl.kacper.sales_api.common.exception.paymentException.PaymentProcessingException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class StripeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StripeService.class);

    private final StripeTransactionService stripeTransactionService;
    private final StripeClient stripeClient;

    @Value("${stripe.webhook-secret}")
    private String stripeWebhookSecret;

    @Autowired
    public StripeService(StripeTransactionService stripeTransactionService, StripeClient stripeClient) {
        this.stripeTransactionService = stripeTransactionService;
        this.stripeClient = stripeClient;
    }

    private Event validateAndCreateEvent(String stripeSignature, String rawBody) {
        try {
            return stripeClient.constructEvent(rawBody, stripeSignature, stripeWebhookSecret);
        } catch (SignatureVerificationException e) {
            throw new StripeWebhookException("Cannot verify stripe webhook request signature. Probably invalid or broken request body", e);
        } catch (RuntimeException e) {
            throw new PaymentProcessingException("Cannot deserialize raw body from webhook", e);
        }
    }

    private <T extends MetadataStore<T>> UUID readOrderIdFromMetadata(T stripeEventObject, String objectID) {
        Map<String, String> metadata = stripeEventObject.getMetadata();
        if (metadata == null)
            throw new StripeWebhookInternalException("INTERNAL CONTRACT: Cannot read metadata of StripeObject=%s. Permanently invalid stripeObject.".formatted(objectID));

        String orderIdRaw = metadata.get("orderId");

        if (orderIdRaw == null || orderIdRaw.isBlank())
            throw new StripeWebhookInternalException("INTERNAL CONTRACT: Cannot read Order metadata of StripeObject=%s because request does not contain orderId. Permanently invalid stripeObject".formatted(objectID));

        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdRaw);
        } catch (IllegalArgumentException e) {
            throw new StripeWebhookInternalException("INTERNAL CONTRACT: Cannot convert Order ID from webhook request for StripeObject=%s to Valid Order Entity. Permanently invalid stripeObject".formatted(objectID));
        }

        return orderId;
    }

    private StripeEventContext validateRequestAndBuildStripeEventContext(StripeObject stripeObject, StripeWebhookEvent stripeWebhookEvent) {
        return switch (stripeWebhookEvent) {
            case PI_SUCCEEDED, PI_FAILED, PI_CANCELED -> {
                PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
                String paymentIntentId = paymentIntent.getId();

                if (paymentIntentId == null || paymentIntentId.isBlank())
                    throw new StripeWebhookException("Webhook Payment Intent Event request is malformed: PaymentIntentId is NULL");

                UUID orderId = readOrderIdFromMetadata(paymentIntent, paymentIntentId);
                yield new StripeEventContext(orderId, paymentIntentId, stripeWebhookEvent);
            }
            case REFUND_UPDATED, REFUND_CREATED, REFUND_FAILED -> {
                Refund refund = (Refund) stripeObject;
                String paymentIntentId = refund.getPaymentIntent();
                String refundId = refund.getId();
                String status = refund.getStatus();

                if (refundId == null || refundId.isBlank())
                    throw new StripeWebhookException("Webhook Refund event request is malformed: RefundId is NULL");
                else if (paymentIntentId == null || paymentIntentId.isBlank())
                    throw new StripeWebhookInternalException("INTERNAL CONTRACT: Webhook Refund-Event request for Refund=%s is not compatible with local payment contract: paymentIntentId is NULL".formatted(refundId));
                else if (status == null || status.isBlank())
                    throw new StripeWebhookException("Webhook Refund event request is malformed for Refund=%s: Refund status is NULL".formatted(refundId));

                UUID orderId = readOrderIdFromMetadata(refund, refundId);
                StripeRefundEventContext.StripeRefundStatus stripeRefundStatus = StripeRefundEventContext.StripeRefundStatus.fromValue(status).orElseThrow(() -> new PaymentProcessingException("Not supported type of refund: " + status));
                yield new StripeRefundEventContext(orderId, paymentIntentId, stripeWebhookEvent, refundId, stripeRefundStatus);
            }
        };
    }

    public void handleWebhookEvent(String stripeSignature, String rawBody) {
        Event stripeEvent = validateAndCreateEvent(stripeSignature, rawBody); // VERIFY SIGNATURE OF WEBHOOK

        String eventId = stripeEvent.getId();

        if (eventId == null || eventId.isBlank())
            throw new StripeWebhookException("Webhook event request is malformed: EventId is NULL");

        EventDataObjectDeserializer dataObjectDeserializer = stripeEvent.getDataObjectDeserializer();

        Optional<StripeWebhookEvent> stripeEventOptional = StripeEventHandlerDispatcher.fromValue(stripeEvent.getType());
        if (stripeEventOptional.isEmpty())
            return; // StripeEvent other than [PI_SUCCEED, PI_CANCELED, PI_FAILED, REFUND_UPDATED, REFUND_UPDATED, REFUND_CREATED, REFUND_FAILED]
        StripeWebhookEvent stripeWebhookEventType = stripeEventOptional.get();

        StripeObject stripeObject = dataObjectDeserializer.getObject().orElseThrow(() -> new PaymentProcessingException("Cannot deserialize DataObject from Event object"));

        // Stripe must be one of the value included in [PI_SUCCEED, PI_CANCELED, PI_FAILED, REFUND_UPDATED, REFUND_CREATED, REFUND_FAILED]
        StripeEventContext stripeEventContext;
        try {
            stripeEventContext = validateRequestAndBuildStripeEventContext(stripeObject, stripeWebhookEventType);
        } catch (StripeWebhookInternalException e) {
            LOGGER.error(e.getMessage());
            return; // ACK permanently invalid event - retry cannot repair it
        }

        // PROCESSING
        try {
            stripeTransactionService.processEvent(stripeEventContext, eventId);
        } catch (CannotAcquireLockException e) {
            throw new PaymentProcessingException("Cannot process this payment at this moment because payment is already claimed by other process", e);
        } catch (NoSuchDbRecordException e) {
            LOGGER.error("""
                    Message: {}
                    Corrupted webhook request for non existing Order
                    """, e.getMessage(), e);
            return; // ACK permanently invalid event - retry cannot repair it
        } catch (PaymentIntentStateMismatchException e) {
            PaymentIntentStateMismatchException.PaymentIntentMismatchReason paymentIntentMismatchReason = e.getMismatchReason();

            switch (paymentIntentMismatchReason) {
                case PAYMENT_INTENT_ID_MISMATCH, INVALID_LOCAL_PAYMENT_STATE -> {
                    LOGGER.error(e.getMessage(), e);
                    return;
                }
                case LOCAL_PAYMENT_INTENT_MISSING -> {
                    LOGGER.error(e.getMessage(), e);
                    throw new PaymentProcessingException("Order state does not match to Stripe state, probably Order is still waiting for payment initializing update", e);
                }
            }
        } catch (RefundStateMismatchException e) {
            RefundStateMismatchException.RefundMismatchReason refundMismatchReason = e.getMismatchReason();
            switch (refundMismatchReason) {
                case REFUND_ID_MISMATCH, INVALID_LOCAL_REFUND_STATE -> {
                    LOGGER.error(e.getMessage(), e);
                    return;
                }
                case LOCAL_REFUND_ID_MISSING -> {
                    LOGGER.error(e.getMessage(), e);
                    throw new PaymentProcessingException("Order state does not match to Stripe state, probably Order is still waiting for refund initializing update", e);
                }
            }
        }
    }


}
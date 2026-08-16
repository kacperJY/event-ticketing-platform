package pl.kacper.sales_api.domain.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.common.exception.ConcurrencyClaimException;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.common.exception.paymentException.InitializationPaymentException;
import pl.kacper.sales_api.common.exception.paymentException.PaymentIntentStateMismatchException;
import pl.kacper.sales_api.domain.order.utils.StatusValidator;

import java.time.Instant;
import java.util.UUID;

@Service
public class OrderTransactionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderTransactionService.class);

    private final OrderRepository orderRepository;
    private final OrderExpirationCleaner orderExpirationCleaner;

    @Autowired
    public OrderTransactionService(OrderRepository orderRepository, OrderExpirationCleaner orderExpirationCleaner) {
        this.orderRepository = orderRepository;
        this.orderExpirationCleaner = orderExpirationCleaner;
    }

    void validateOrder(OrderEntity orderEntity) {
        // ONLY PENDING - pass
        if (!StatusValidator.validateOrderStatus(orderEntity.getOrderStatus()))
            throw new InitializationPaymentException(StatusValidator.createMessageForInvalidOrderStatus(orderEntity.getOrderStatus()));

        // PENDING & NOT_INITIALIZED - pass
        if (!StatusValidator.validatePaymentStatus(orderEntity.getPaymentStatus()))
            throw new InitializationPaymentException(StatusValidator.createMessageForInvalidPaymentStatus(orderEntity.getPaymentStatus()));
    }

    private void checkOrderExpirationAndTryUpdate(UUID orderId){
        try {
            orderExpirationCleaner.checkIfOrderExpiresTimePastAndClean(orderId); // Lock TX
        } catch (ConcurrencyClaimException e){
            throw new InitializationPaymentException("Order[orderId=%s] is currently being processed by other process. Try again later".formatted(orderId),e);
        }
    }

    @Transactional
    void validateOrderAfterRetrieve(UUID orderId) {
        checkOrderExpirationAndTryUpdate(orderId);// Lock TX

        OrderEntity orderEntity = orderRepository.findOrderEntityWithUserByOrderId(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Order with orderId=%s does not exist".formatted(orderId)));

        validateOrder(orderEntity);

        if (orderEntity.getPaymentStatus() != PaymentStatus.PENDING)
            throw new InitializationPaymentException("Cannot retrieve existing Order[orderId=%s] payment because its status is not PENDING anymore".formatted(orderId));
    }


    @Transactional
    OrderEntity validateOrderBefore(UUID orderId, UserDetails userDetails){
        checkOrderExpirationAndTryUpdate(orderId); // Lock TX

        OrderEntity orderEntity = orderRepository.findOrderEntityWithUserByOrderId(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Order with orderId=%s does not exist".formatted(orderId)));

        if (!orderEntity.getPurchaser().getEmail().equals(userDetails.getUsername()))
            throw new AccessDeniedException("Cannot get access to order that you are not owner");

        validateOrder(orderEntity);

        if (orderEntity.getPaymentStatus() == PaymentStatus.PENDING && orderEntity.getStripePaymentIntentId() == null) {
            LOGGER.error("""
                    Invalid Order state. Order cannot be PENDING while does not have its payment intent ID.
                    Probably caused by inconsistent Order update. Order payment data should not be updated out of payment flow.
                    """);
            throw new PaymentIntentStateMismatchException("Invalid Order state, probably caused by inconsistent Order update. Order needs to be normalized");
        }

        if(orderEntity.getPaymentStatus() == PaymentStatus.NOT_INITIALIZED && orderEntity.getStripePaymentIntentId() != null) {
            LOGGER.error("""
                    Invalid Order state. Order cannot have paymentIntentId while payment status of Order is NOT_INITIALIZED.
                    Probably caused by inconsistent Order update. Order payment data should not be updated out of payment flow.
                    """);
            throw new PaymentIntentStateMismatchException("Invalid Order state, probably caused by inconsistent Order update. Order needs to be normalized");
        }

        return orderEntity;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void tryUpdateOrderEntityAfterPaymentInitialization(UUID orderId, String stripePaymentIntentId, Instant initializedAt) {
        checkOrderExpirationAndTryUpdate(orderId); // Lock TX

        OrderEntity orderEntity = orderRepository.findByOrderIdWithLockingNoWait(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Order with orderId=%s does not exist".formatted(orderId)));

        validateOrder(orderEntity);

        // NOT_INITIALIZED - update Order
        if (orderEntity.getPaymentStatus() == PaymentStatus.NOT_INITIALIZED && orderEntity.getStripePaymentIntentId() == null) {
            orderEntity.setPaymentInitializedAt(initializedAt);
            orderEntity.setPaymentStatus(PaymentStatus.PENDING);
            orderEntity.setStripePaymentIntentId(stripePaymentIntentId);
            return;
        } else if (orderEntity.getPaymentStatus() == PaymentStatus.PENDING &&
                orderEntity.getStripePaymentIntentId() != null &&
                orderEntity.getStripePaymentIntentId().equals(stripePaymentIntentId))
            return;

        LOGGER.error("""
                     Order[orderId={}]: Invalid state between local paymentIntentID={} and incoming paymentIntentID={}
                     State of Order payment has to be normalized.
                """, orderId, orderEntity.getStripePaymentIntentId(), stripePaymentIntentId);
        throw new PaymentIntentStateMismatchException("Invalid Order payment state between local state and Stripe state - Order[orderId=%s]".formatted(orderId));
    }


}

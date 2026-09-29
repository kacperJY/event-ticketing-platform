package pl.kacper.sales_api.domain.order.stripe.refund;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.service.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.common.exception.paymentException.RefundStateMismatchException;
import pl.kacper.sales_api.domain.order.OrderEntity;

import java.util.List;
import java.util.UUID;

@Service
class RefundInitializerService {
    private final static Logger LOGGER = LoggerFactory.getLogger(RefundInitializerService.class);

    private final RefundInitializerTransactionService refundInitializerTransactionService;
    private final StripeClient stripeClient;

    @Value("${database.batch-size}")
    private int batchSize;

    @Value("${stripe.idempotency-key.refund-prefix}")
    private String idempotencyKeyPrefix;

    @Autowired
    RefundInitializerService(RefundInitializerTransactionService refundInitializerTransactionService, StripeClient stripeClient) {
        this.refundInitializerTransactionService = refundInitializerTransactionService;
        this.stripeClient = stripeClient;
    }

    void scanAndInitRefunds() {
        List<OrderEntity> orderRefundCandidate = refundInitializerTransactionService.findOrderRefundCandidates(batchSize);// TX1

        for (OrderEntity orderEntity : orderRefundCandidate) {
            try {
                Refund refund = createRefund(orderEntity);
                String stripeRefundId = refund.getId();
                UUID orderId = orderEntity.getOrderId();
                refundInitializerTransactionService.validateAndUpdateAfterRefundCreate(orderId, stripeRefundId);
            } catch (StripeException e) {
//                if (e.getCode() != null && e.getCode().equals("charge_already_refunded")) {
//                    ### SKIPPED
//                }
                LOGGER.error("""
                        Creating refund
                        OrderId: {}
                        Error code: {}
                        Status code: {}
                        Message: {}
                        """, orderEntity.getOrderId(), e.getCode(), e.getStatusCode(), e.getMessage(), e);
            } catch (RefundStateMismatchException | NoSuchDbRecordException e) {
                LOGGER.error(e.getMessage(), e);
            } catch (CannotAcquireLockException e) {
                LOGGER.debug("Could not update Order[orderId={}] after create refund, because Order is claimed by other process", orderEntity.getOrderId(), e);
            }
        }
    }


    private Refund createRefund(OrderEntity orderEntity) throws StripeException {
        RefundCreateParams refundCreateParams = new RefundCreateParams.Builder()
                .setPaymentIntent(orderEntity.getStripePaymentIntentId())
                .putMetadata("orderId", orderEntity.getOrderId().toString())
                .setAmount(orderEntity.getTotalAmount())
                .build();

        RequestOptions requestOptions = RequestOptions.builder()
                .setIdempotencyKey(idempotencyKeyPrefix + orderEntity.getOrderId())
                .build();

        RefundService refundService = stripeClient.v1().refunds();
        return refundService.create(refundCreateParams, requestOptions);
    }
}

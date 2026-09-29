package pl.kacper.sales_api.domain.order.stripe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class StripeTransactionService {

    private final StripeEventRepository stripeEventRepository;
    private final StripeEventHandler stripeEventHandler;

    private final static Logger LOGGER = LoggerFactory.getLogger(StripeTransactionService.class);

    @Autowired
    public StripeTransactionService(StripeEventRepository stripeEventRepository, StripeEventHandler stripeEventHandler) {
        this.stripeEventRepository = stripeEventRepository;
        this.stripeEventHandler = stripeEventHandler;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processEvent(StripeEventContext stripeEventContext, String eventId) {
        int inserts = stripeEventRepository.insertOnConflictDoNothing(
                eventId,
                stripeEventContext.getOrderId(),
                stripeEventContext.getPaymentIntentId(),
                stripeEventContext.getStripeWebhookEvent().getValue(), Instant.now()
        );

        // FOR CLAIM EVENT RACE - successfully response
        if (inserts == 0) return;

        // FOR LOCK OBTAIN RACE - rollback
        StripeEventProcessingResult result = stripeEventHandler.handle(stripeEventContext);

        switch (result.state()) {
            case APPLIED -> LOGGER.info(result.message());
            case IDEMPOTENT_NO_OP -> LOGGER.debug(result.message());
            case ACKNOWLEDGED_INCONSISTENT -> LOGGER.error(result.message());
        }
    }
}

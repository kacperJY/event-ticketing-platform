package pl.kacper.sales_api.domain.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import pl.kacper.sales_api.common.exception.ConcurrencyClaimException;

import java.util.UUID;

@Component
public class OrderExpirationCleaner {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderExpirationCleaner.class);

    @Value("${database.batch-size}")
    private int batchSize;

    private final OrderExpirationTransactionService orderExpirationTransactionService;

    @Autowired
    public OrderExpirationCleaner(OrderExpirationTransactionService orderExpirationTransactionService) {
        this.orderExpirationTransactionService = orderExpirationTransactionService;
    }

    private static final int MAX_BATCHES_NUMBER = 5;

    public void scanAndCleanExpiredOrders() {
        PageRequest pageRequest = PageRequest.of(0, batchSize, Sort.by("expiresAt").ascending()); // ALWAYS FIRST-PAGE

        for (int i = 0; i < MAX_BATCHES_NUMBER; i++) {
            int fetchedNumber = orderExpirationTransactionService.findAndUpdateExpiredOrders(pageRequest);

            if (fetchedNumber != batchSize) break; // TERMINATE
        }
    }

    public void checkIfOrderExpiresTimePastAndClean(UUID orderId) {
        try {
            orderExpirationTransactionService.expireOrderIfEligible(orderId);
        } catch (CannotAcquireLockException e) {
            LOGGER.debug("Immediate cleaning process: Order[orderId={}] not possible to check order expiration, because order is already claimed by other process", orderId);
            throw new ConcurrencyClaimException("Order[orderId=%s] is claimed by other process, cannot currently check expiration".formatted(orderId),e);
        }
    }
}

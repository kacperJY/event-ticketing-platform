package pl.kacper.sales_api.domain.order;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Profile("!test")
public class OrderExpirationScheduleScannerService {

    private final OrderExpirationCleaner orderExpirationCleaner;

    public OrderExpirationScheduleScannerService(OrderExpirationCleaner orderExpirationCleaner) {
        this.orderExpirationCleaner = orderExpirationCleaner;
    }

    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.SECONDS)
    public void scanExpiredOrdersAndUpdate() {

        this.orderExpirationCleaner.scanAndCleanExpiredOrders();
    }
}

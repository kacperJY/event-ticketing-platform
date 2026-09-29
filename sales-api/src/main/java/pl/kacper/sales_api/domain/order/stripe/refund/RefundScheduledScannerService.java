package pl.kacper.sales_api.domain.order.stripe.refund;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Profile("!test")
@Component
class RefundScheduledScannerService {

    private final RefundInitializerService refundInitializerService;

    RefundScheduledScannerService(RefundInitializerService refundInitializerService) {
        this.refundInitializerService = refundInitializerService;
    }

    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.SECONDS)
    void scanAndInitRefund() {
        refundInitializerService.scanAndInitRefunds();
    }
}

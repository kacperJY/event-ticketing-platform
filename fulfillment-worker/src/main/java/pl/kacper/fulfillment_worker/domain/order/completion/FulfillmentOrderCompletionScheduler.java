package pl.kacper.fulfillment_worker.domain.order.completion;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Profile("!test")
@Component
class FulfillmentOrderCompletionScheduler {

    private final FulfillmentOrderCompletionService fulfillmentOrderCompletionService;

    public FulfillmentOrderCompletionScheduler(FulfillmentOrderCompletionService fulfillmentOrderCompletionService) {
        this.fulfillmentOrderCompletionService = fulfillmentOrderCompletionService;
    }

    @Scheduled(fixedRate = 15,timeUnit = TimeUnit.SECONDS)
    public void completeFulfillmentOrders(){
        fulfillmentOrderCompletionService.scanAndCompleteFulfillmentOrder();
    }
}

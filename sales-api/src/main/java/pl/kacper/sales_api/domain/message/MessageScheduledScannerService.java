package pl.kacper.sales_api.domain.message;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Profile("!test")
public class MessageScheduledScannerService {

    private final MessagePublisher messagePublisher;

    @Autowired
    public MessageScheduledScannerService(MessagePublisher messagePublisher) {
        this.messagePublisher = messagePublisher;
    }

    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.SECONDS)
    public void scanAndSendMessage() {
        messagePublisher.scanAndPublishMessage();
    }
}

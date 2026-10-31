package pl.kacper.fulfillment_worker.domain.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pl.kacper.fulfillment_worker.domain.message.CompletedOrderMessageResolver;
import pl.kacper.fulfillment_worker.domain.message.dto.payload.CompletedOrderMessagePayloadV1;

import java.util.UUID;

@Component
class CompletedOrderMessageConsumer {

    @Value("${rabbitmq.fulfillment-worker.completed-order.consumer-name}")
    private String consumerName;

    private final static Logger LOGGER = LoggerFactory.getLogger(CompletedOrderMessageConsumer.class);
    private final CompletedOrderMessageResolver completedOrderMessageResolver;
    private final CompletedOrderMessageTransactionService completedOrderMessageTransactionService;
    private final CompletedOrderMessagePayloadValidator completedOrderMessagePayloadValidator;

    @Autowired
    public CompletedOrderMessageConsumer(CompletedOrderMessageResolver completedOrderMessageResolver, CompletedOrderMessageTransactionService completedOrderMessageTransactionService,
                                         CompletedOrderMessagePayloadValidator completedOrderMessagePayloadValidator) {
        this.completedOrderMessageResolver = completedOrderMessageResolver;
        this.completedOrderMessageTransactionService = completedOrderMessageTransactionService;
        this.completedOrderMessagePayloadValidator = completedOrderMessagePayloadValidator;
    }

    @RabbitListener(queues = "${rabbitmq.sales-api.completed-order.queue-name}", containerFactory = "rabbitListenerContainerFactory")
    public void consumeMessage(Message message) {

        CompletedOrderMessageResolver.ResolvedPayloadByMessageId resolvedPayloadByMessageId = completedOrderMessageResolver.resolveMessage(message);
        UUID messageId = resolvedPayloadByMessageId.messageId();
        CompletedOrderMessagePayloadV1 completedOrderMessagePayloadV1 = resolvedPayloadByMessageId.completedOrderMessagePayloadV1();
        LOGGER.info("Processing message: {}", messageId);

        completedOrderMessagePayloadValidator.validate(completedOrderMessagePayloadV1);

        completedOrderMessageTransactionService.processMessage(consumerName, messageId, completedOrderMessagePayloadV1);
    }
}
package pl.kacper.fulfillment_worker.domain.order;

import jakarta.persistence.EntityManager;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import pl.kacper.fulfillment_worker.BaseMessagingIT;
import pl.kacper.fulfillment_worker.domain.message.CompletedOrderMessageResolver;
import pl.kacper.fulfillment_worker.domain.message.dto.payload.CompletedOrderMessagePayloadV1;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

class CompletedOrderMessageConsumerIT extends BaseMessagingIT {

    private final EntityManager entityManager;

    @Autowired
    public CompletedOrderMessageConsumerIT(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @MockitoSpyBean
    private CompletedOrderMessageResolver completedOrderMessageResolver;

    @MockitoSpyBean
    private CompletedOrderMessageTransactionService completedOrderMessageTransactionService;

    @MockitoSpyBean
    private CompletedOrderMessagePayloadValidator completedOrderMessagePayloadValidator;

    @Value("${rabbitmq.sales-api.completed-order.routing-key}")
    private String completedOrderRoutingKey;

    @Value("${rabbitmq.exchange.main-exchange}")
    private String mainExchange;

    @Value("${rabbitmq.fulfillment-worker.completed-order.dlq}")
    private String deadLetterQueueName;

    @Value("${rabbitmq.sales-api.completed-order.queue-name}")
    private String completedOrderQueueName;

    @Value("${message-integration.sales-api.headers.operation-type}")
    String operationTypeHeaderName;
    @Value("${message-integration.sales-api.headers.aggregate-type}")
    String aggregateTypeHeaderName;
    @Value("${message-integration.sales-api.headers.message-payload-version}")
    String messagePayloadVersionHeaderName;

    @Value("${message-integration.sales-api.contract.completed-order.operation-type}")
    String operationTypeContractValue;
    @Value("${message-integration.sales-api.contract.completed-order.aggregate-type}")
    String aggregateTypeContractValue;
    @Value("${message-integration.sales-api.contract.completed-order.message-payload-version}")
    String messagePayloadVersionContractValue;

    @Value("${message-integration.sales-api.headers.aggregate-id}")
    String aggregateIdHeaderName;

    @Value("${rabbitmq.consumer.delay}")
    private int retryDelay;

    @Value("${rabbitmq.consumer.multiplier}")
    private double retryDelayMultiplier;

    @Value("${rabbitmq.consumer.max-retries}")
    private int maxRetries;

    @Value("${rabbitmq.consumer.max-delay}")
    private int maxDelay;

    private static final String CONTENT_TYPE = "application/json";
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    private static final Instant DATE = Instant.now();
    private static final UUID VALID_ORDER_ID = UUID.randomUUID();
    private static final UUID VALID_MESSAGE_ID = UUID.randomUUID();
    private static final CompletedOrderMessagePayloadV1 VALID_PAYLOAD = new CompletedOrderMessagePayloadV1(
            VALID_ORDER_ID,
            "mail@gmail.com",
            DATE,
            List.of(
                    new CompletedOrderMessagePayloadV1.Item(
                            1L,
                            "Summer Music Festival 2026",
                            "United Kingdom",
                            "London",
                            "Wembley Park Drive",
                            "HA9 0WS",
                            "00-111",
                            DATE,
                            "Music Festivals",
                            "Section B, Row 14, Seat 22"
                    )
            )
    );

    private Message buildMessageWithBody(
            String messageId,
            Object operationType,
            Object aggregateType,
            Object aggregateId,
            Object payloadVersion,
            Object payloadDto
    ) {
        String json = objectMapper.writeValueAsString(payloadDto);
        return buildMessage(messageId, CONTENT_TYPE, operationType, aggregateType, aggregateId, payloadVersion, json.getBytes(DEFAULT_CHARSET));
    }

    private Message buildMessage(
            String messageId,
            String contentType,
            Object operationType,
            Object aggregateType,
            Object aggregateId,
            Object payloadVersion,
            byte[] bytes
    ) {
        MessageProperties messageProperties = buildMessageProperties(messageId, contentType, operationType, aggregateType, aggregateId, payloadVersion);
        return MessageBuilder.withBody(bytes).andProperties(messageProperties).build();
    }

    private MessageProperties buildMessageProperties(
            String messageId,
            String contentType,
            Object operationType,
            Object aggregateType,
            Object aggregateId,
            Object payloadVersion
    ) {
        return MessagePropertiesBuilder.newInstance()
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(messageId)
                .setContentType(contentType)
                .setContentEncoding(DEFAULT_CHARSET.name())
                .setHeader(aggregateTypeHeaderName, aggregateType)
                .setHeader(aggregateIdHeaderName, aggregateId)
                .setHeader(operationTypeHeaderName, operationType)
                .setHeader(messagePayloadVersionHeaderName, payloadVersion)
                .build();
    }

    private Message buildValidMessage() {
        return buildMessageWithBody(
                String.valueOf(VALID_MESSAGE_ID),
                operationTypeContractValue,
                aggregateTypeContractValue,
                VALID_ORDER_ID,
                messagePayloadVersionContractValue,
                VALID_PAYLOAD
        );
    }

    private CompletableFuture<CorrelationData.Confirm> verifySentMessageAsync(CorrelationData correlationData) {
        return correlationData.getFuture().orTimeout(5, TimeUnit.SECONDS).whenComplete((confirm, throwable) -> {
            ReturnedMessage returned = correlationData.getReturned();
            if (throwable != null)
                throw new RuntimeException("Message couldn't be sent to RabbitMQ :: " + throwable.getMessage(), throwable);
            if (returned != null)
                throw new RuntimeException("Routing message failure - There is no binding for typed routing-key probably ::" + returned.getReplyText());
            if (!confirm.ack())
                throw new RuntimeException("RabbitMQ couldn't confirmed message :: Reason=" + confirm.reason());
        });
    }

    private long syncWaitForResult(Duration timeoutPerRetry, int maxRetries, Supplier<Long> countQuery) {
        int retries = maxRetries;
        long count = 0;
        while (retries > 0) {
            count = countQuery.get();
            if (count > 0 || (retries - 1) == 0) break;

            try {
                Thread.sleep(timeoutPerRetry);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Assertions.fail("Main test thread has been interrupted while waiting for next retry - Thread=" + Thread.currentThread().getName());
            }
            retries--;
        }
        return count;
    }

    @Test
    void shouldSuccessfullySendAndConsumeMessage() {
        Message message = buildValidMessage();

        CorrelationData correlationData = new CorrelationData(String.valueOf(VALID_MESSAGE_ID));
        rabbitTemplate.send(mainExchange, completedOrderRoutingKey, message, correlationData);

        try {
            verifySentMessageAsync(correlationData).join();
        } catch (RuntimeException e) {
            Assertions.fail(e.getMessage(), e);
        }

        int retries = 10;
        Duration timeout = Duration.ofMillis(500);

        long count = syncWaitForResult(
                timeout,
                retries,
                () -> entityManager.createQuery("select count (*) from ProcessedMessageEntity", Long.class).getSingleResult());

        if (count == 0) Assertions.fail("Application failure: Message could not be consumed and processed correctly");

        Assertions.assertThat(count).withFailMessage("Expected ProcessedMessageEntity: 1 -- Provided: " + count).isEqualTo(1);

        long orderCount = entityManager.createQuery("select count(*) from FulfillmentOrderEntity", Long.class).getSingleResult();
        Assertions.assertThat(orderCount).withFailMessage("Expected FulfillmentOrderEntity: 1 -- Provided: " + orderCount).isEqualTo(1);

        long ticketCount = entityManager.createQuery("select count(*) from TicketEntity te where te.fulfillmentOrderEntity.sourceOrderId=:orderId", Long.class)
                .setParameter("orderId", VALID_ORDER_ID)
                .getSingleResult();
        Assertions.assertThat(ticketCount).withFailMessage("Expected TicketEntity: %s -- Provided: %s".formatted(VALID_PAYLOAD.itemList().size(), ticketCount)).isEqualTo(VALID_PAYLOAD.itemList().size());
    }

    @Test
    void shouldDeadLetterMessageAfterPermanentRoutingException() {
        final String invalidAggregateType = "INVALID AGGREGATE TYPE";

        Message message = buildMessageWithBody(
                String.valueOf(VALID_MESSAGE_ID),
                operationTypeContractValue,
                invalidAggregateType,
                VALID_ORDER_ID.toString(),
                messagePayloadVersionContractValue,
                VALID_PAYLOAD
        );

        CorrelationData correlationData = new CorrelationData(String.valueOf(VALID_MESSAGE_ID));
        rabbitTemplate.send(mainExchange, completedOrderRoutingKey, message, correlationData);

        try {
            verifySentMessageAsync(correlationData).join();
        } catch (RuntimeException e) {
            Assertions.fail(e.getMessage(), e);
        }

        Message deadLetterMessage = rabbitTemplate.receive(deadLetterQueueName, Duration.ofSeconds(5).toMillis());

        Assertions.assertThat(deadLetterMessage).withFailMessage("Rejected message by consumer did not get into dead letter queue. Dead letter queue is empty").isNotNull();
        MessageProperties deadLetterMessageMessageProperties = deadLetterMessage.getMessageProperties();
        Assertions.assertThat(deadLetterMessageMessageProperties.getMessageId()).isEqualTo(VALID_MESSAGE_ID.toString());

        CompletedOrderMessagePayloadV1 completedOrderMessagePayloadV1 = objectMapper.readValue(deadLetterMessage.getBody(), CompletedOrderMessagePayloadV1.class);
        Assertions.assertThat(completedOrderMessagePayloadV1.orderId()).isEqualTo(VALID_ORDER_ID);
        Object originalQueue = deadLetterMessageMessageProperties.getHeader("x-last-death-queue");
        Object deadLetteredReason = deadLetterMessageMessageProperties.getHeader("x-last-death-reason");
        Assertions.assertThat(originalQueue).withFailMessage("Dead lettering message has ended with failure. Header x-last-death-queue is empty").isNotNull();
        Assertions.assertThat(deadLetteredReason).withFailMessage("Dead lettering message has ended with failure. Header x-last-death-reason is empty").isNotNull();
        Assertions.assertThat(originalQueue.toString()).isEqualTo(completedOrderQueueName);
        Assertions.assertThat(deadLetteredReason.toString()).isEqualTo("rejected");

        long count = entityManager.createQuery("select count (*) from ProcessedMessageEntity", Long.class).getSingleResult();
        Assertions.assertThat(count).withFailMessage("Expected ProcessedMessageEntity: 0 -- Provided: " + count).isEqualTo(0);

        long orderCount = entityManager.createQuery("select count(*) from FulfillmentOrderEntity", Long.class).getSingleResult();
        Assertions.assertThat(orderCount).withFailMessage("Expected FulfillmentOrderEntity: 0 -- Provided: " + orderCount).isEqualTo(0);

        long ticketCount = entityManager.createQuery("select count(*) from TicketEntity te where te.fulfillmentOrderEntity.sourceOrderId=:orderId", Long.class)
                .setParameter("orderId", VALID_ORDER_ID)
                .getSingleResult();
        Assertions.assertThat(ticketCount).withFailMessage("Expected TicketEntity: 0 -- Provided: " + ticketCount).isEqualTo(0);

        Mockito.verify(completedOrderMessageResolver, Mockito.only()).resolveMessage(ArgumentMatchers.any());
    }

    @Test
    void shouldSuccessfullyConsumeAndProcessMessageAfterRetry() {
        Message message = buildValidMessage();

        Mockito.doThrow(RuntimeException.class).doCallRealMethod().when(completedOrderMessageResolver).resolveMessage(ArgumentMatchers.any());

        CorrelationData correlationData = new CorrelationData(String.valueOf(VALID_MESSAGE_ID));
        rabbitTemplate.send(mainExchange, completedOrderRoutingKey, message, correlationData);

        try {
            verifySentMessageAsync(correlationData).join();
        } catch (RuntimeException e) {
            Assertions.fail(e.getMessage(), e);
        }


        int retries = 10;
        Duration timeout = Duration.ofMillis(500);

        long count = syncWaitForResult(
                timeout,
                retries,
                () -> entityManager.createQuery("select count (*) from ProcessedMessageEntity", Long.class).getSingleResult());

        if (count == 0) Assertions.fail("Application failure: Message could not be consumed and processed correctly");

        Assertions.assertThat(count).withFailMessage("Expected ProcessedMessageEntity: 1 -- Provided: " + count).isEqualTo(1);

        long orderCount = entityManager.createQuery("select count(*) from FulfillmentOrderEntity", Long.class).getSingleResult();
        Assertions.assertThat(orderCount).withFailMessage("Expected FulfillmentOrderEntity: 1 -- Provided: " + orderCount).isEqualTo(1);

        long ticketCount = entityManager.createQuery("select count(*) from TicketEntity te where te.fulfillmentOrderEntity.sourceOrderId=:orderId", Long.class)
                .setParameter("orderId", VALID_ORDER_ID)
                .getSingleResult();
        Assertions.assertThat(ticketCount).withFailMessage("Expected TicketEntity: %s -- Provided: %s".formatted(VALID_PAYLOAD.itemList().size(), ticketCount)).isEqualTo(VALID_PAYLOAD.itemList().size());

        Mockito.verify(completedOrderMessageResolver, Mockito.times(2)).resolveMessage(ArgumentMatchers.any());
        Mockito.verify(completedOrderMessageTransactionService, Mockito.times(1)).processMessage(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(completedOrderMessagePayloadValidator, Mockito.times(1)).validate(ArgumentMatchers.any());

        Message deadLetterMessage = rabbitTemplate.receive(deadLetterQueueName);
        Assertions.assertThat(deadLetterMessage).isNull();
    }

    @Test
    void shouldDeadLetterMessageAfterAllRetriesAreExhausted() {
        Message message = buildValidMessage();

        Mockito.doThrow(RuntimeException.class).when(completedOrderMessageResolver).resolveMessage(ArgumentMatchers.any());

        CorrelationData correlationData = new CorrelationData(String.valueOf(VALID_MESSAGE_ID));
        rabbitTemplate.send(mainExchange, completedOrderRoutingKey, message, correlationData);

        try {
            verifySentMessageAsync(correlationData).join();
        } catch (RuntimeException e) {
            Assertions.fail(e.getMessage(), e);
        }

        double maxWaitingTime = retryDelay;
        double currentValue = retryDelay;
        for (int i = 1; i < maxRetries; i++) {
            currentValue = (currentValue * retryDelayMultiplier) > maxDelay ? maxDelay : (currentValue * retryDelayMultiplier);
            maxWaitingTime += currentValue;
        }
        int extraTime = 3;

        int finalMaxWaitingTime = ((int) maxWaitingTime) + extraTime;

        Message deadLetterMessage = rabbitTemplate.receive(deadLetterQueueName, Duration.ofSeconds(finalMaxWaitingTime).toMillis());

        Assertions.assertThat(deadLetterMessage).withFailMessage("Rejected message by consumer did not get into dead letter queue. Dead letter queue is empty").isNotNull();
        MessageProperties deadLetterMessageMessageProperties = deadLetterMessage.getMessageProperties();
        Assertions.assertThat(deadLetterMessageMessageProperties.getMessageId()).isEqualTo(VALID_MESSAGE_ID.toString());

        CompletedOrderMessagePayloadV1 completedOrderMessagePayloadV1 = objectMapper.readValue(deadLetterMessage.getBody(), CompletedOrderMessagePayloadV1.class);
        Assertions.assertThat(completedOrderMessagePayloadV1.orderId()).isEqualTo(VALID_ORDER_ID);
        Object originalQueue = deadLetterMessageMessageProperties.getHeader("x-last-death-queue");
        Object deadLetteredReason = deadLetterMessageMessageProperties.getHeader("x-last-death-reason");
        Assertions.assertThat(originalQueue).withFailMessage("Dead lettering message has ended with failure. Header x-last-death-queue is empty").isNotNull();
        Assertions.assertThat(deadLetteredReason).withFailMessage("Dead lettering message has ended with failure. Header x-last-death-reason is empty").isNotNull();
        Assertions.assertThat(originalQueue.toString()).isEqualTo(completedOrderQueueName);
        Assertions.assertThat(deadLetteredReason.toString()).isEqualTo("rejected");

        long count = entityManager.createQuery("select count (*) from ProcessedMessageEntity", Long.class).getSingleResult();
        Assertions.assertThat(count).withFailMessage("Expected ProcessedMessageEntity: 0 -- Provided: " + count).isEqualTo(0);

        long orderCount = entityManager.createQuery("select count(*) from FulfillmentOrderEntity", Long.class).getSingleResult();
        Assertions.assertThat(orderCount).withFailMessage("Expected FulfillmentOrderEntity: 0 -- Provided: " + orderCount).isEqualTo(0);

        long ticketCount = entityManager.createQuery("select count(*) from TicketEntity te where te.fulfillmentOrderEntity.sourceOrderId=:orderId", Long.class)
                .setParameter("orderId", VALID_ORDER_ID)
                .getSingleResult();
        Assertions.assertThat(ticketCount).withFailMessage("Expected TicketEntity: 0 -- Provided: " + ticketCount).isEqualTo(0);

        Mockito.verify(completedOrderMessageResolver, Mockito.times(maxRetries + 1)).resolveMessage(ArgumentMatchers.any());
    }
}

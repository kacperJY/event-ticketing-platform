package pl.kacper.sales_api.domain.message;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import pl.kacper.sales_api.domain.BaseIT;
import pl.kacper.sales_api.domain.event.EventCategory;
import pl.kacper.sales_api.domain.event.EventEntity;
import pl.kacper.sales_api.domain.event.EventRepository;
import pl.kacper.sales_api.domain.event.dto.Address;
import pl.kacper.sales_api.domain.message.dto.MessageType;
import pl.kacper.sales_api.domain.message.dto.event.CreateEventMessagePayloadDto;
import pl.kacper.sales_api.domain.message.property.AggregateType;
import pl.kacper.sales_api.domain.message.property.MessagePayloadVersion;
import pl.kacper.sales_api.domain.message.property.OperationType;
import pl.kacper.sales_api.domain.seat.CreateEventMessageConsumer;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

class MessageConsumerIT extends BaseIT {


    private final CreateEventMessageConsumer createEventMessageConsumer;
    private final SeatRepository seatRepository;
    private final ObjectMapper objectMapper;
    private final ProcessedMessageRepository processedMessageRepository;
    private final EventRepository eventRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange.main-exchange}")
    private String mainExchangeName;
    @Value("${rabbitmq.sales-api.create-event.routing-key}")
    private String createEventRoutingKey;
    @Value("${rabbitmq.sales-api.create-event.queue-name}")
    private String createEventQueueName;

    @Value("${rabbitmq.sales-api.create-event.dlq}")
    private String createEventDLQ;

    private final static MessageType CREATE_EVENT_MESSAGETYPE = new MessageType(AggregateType.EVENT, OperationType.CREATE, MessagePayloadVersion.V1);

    @Autowired
    public MessageConsumerIT(CreateEventMessageConsumer createEventMessageConsumer,
                             SeatRepository seatRepository, ObjectMapper objectMapper, ProcessedMessageRepository processedMessageRepository,
                             EventRepository eventRepository, RabbitTemplate rabbitTemplate) {
        this.createEventMessageConsumer = createEventMessageConsumer;
        this.seatRepository = seatRepository;
        this.objectMapper = objectMapper;
        this.processedMessageRepository = processedMessageRepository;
        this.eventRepository = eventRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    private Message initializeEventCreateTestMessage(EventEntity eventEntity, MessageType messageType) {
        CreateEventMessagePayloadDto createEventMessagePayloadDto = new CreateEventMessagePayloadDto(
                eventEntity.getEventId(),
                5_000L,
                eventEntity.getPlacesNumber(),
                eventEntity.getName()
        );
        String jsonPayload = objectMapper.writeValueAsString(createEventMessagePayloadDto);
        return buildMessage(jsonPayload, messageType, eventEntity.getEventId());
    }


    private Message initializeEventCreateTestMessage(EventEntity eventEntity, MessageType messageType, Object payloadObj) {
        String jsonPayload = objectMapper.writeValueAsString(payloadObj);
        return buildMessage(jsonPayload, messageType, eventEntity.getEventId());
    }

    private Message initializeEventCreateTestMessage(CreateEventMessagePayloadDto payloadDto, MessageType messageType) {
        String jsonPayload = objectMapper.writeValueAsString(payloadDto);
        return buildMessage(jsonPayload, messageType, payloadDto.eventId());
    }


    private Message buildMessage(String jsonPayload, MessageType messageType, Long eventId) {
        MessageProperties messageProperties = MessagePropertiesBuilder.newInstance()
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(String.valueOf(UUID.randomUUID()))
                .setContentType("application/json")
                .setContentEncoding("UTF-8")
                .setHeader("aggregateType", messageType.aggregateType().name())
                .setHeader("aggregateId", String.valueOf(eventId))
                .setHeader("operationType", messageType.operationType().name())
                .setHeader("payloadVersion", messageType.messagePayloadVersion().name())
                .build();
        return MessageBuilder.withBody(jsonPayload.getBytes(StandardCharsets.UTF_8)).andProperties(messageProperties).build();
    }

    private EventEntity createTestEventEntity() {
        return new EventEntity(
                "test-event", "test-description",
                EventCategory.CINEMA,
                new Address("Poland", "Poznań", "Test-street", "123", "00-000"),
                Instant.now(),
                50);
    }

    @Test
    void shouldConsumeAndSaveProcessedMessage() {
        EventEntity eventEntity = createTestEventEntity();

        eventRepository.save(eventEntity);
        // DETACHED

        Message message = initializeEventCreateTestMessage(eventEntity, CREATE_EVENT_MESSAGETYPE);

        createEventMessageConsumer.consumeMessage(message);

        int seatCount = seatRepository.countByEvent_EventIdAndSeatStatus(eventEntity.getEventId(), SeatStatus.AVAILABLE);

        long processedMessageCount = processedMessageRepository.count();

        Assertions.assertThat(seatCount).isEqualTo(eventEntity.getPlacesNumber());
        Assertions.assertThat(processedMessageCount).isEqualTo(1);
    }

    @Test
    void shouldProcessDuplicateMessageOnlyOnce() {
        EventEntity eventEntity = createTestEventEntity();

        eventRepository.save(eventEntity);
        // DETACHED

        Message message = initializeEventCreateTestMessage(eventEntity, CREATE_EVENT_MESSAGETYPE);

        // #1 - Processed message correctly
        createEventMessageConsumer.consumeMessage(message);

        // #2 - Check duplicate and ignore
        createEventMessageConsumer.consumeMessage(message);

        int seatCount = seatRepository.countByEvent_EventIdAndSeatStatus(eventEntity.getEventId(), SeatStatus.AVAILABLE);

        long processedMessageCount = processedMessageRepository.count();

        Assertions.assertThat(seatCount).isEqualTo(eventEntity.getPlacesNumber());
        Assertions.assertThat(processedMessageCount).isEqualTo(1);
    }

    private CompletableFuture<Void> consumeMessageAsync(Message message, CountDownLatch countDownLatch, Executor executor) {
        return CompletableFuture.runAsync(() -> {
            try {
                countDownLatch.countDown();

                boolean await = countDownLatch.await(3, TimeUnit.SECONDS);

                if (!await)
                    throw new TimeoutException("Waiting for %s - out of time".formatted(Thread.currentThread().getName()));

                createEventMessageConsumer.consumeMessage(message);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Assertions.fail("Test MessageConsumer Thread has been interrupted");
            } catch (TimeoutException e) {
                Assertions.fail("Test infrastructure failure");
            }
        }, executor);

    }

    @Test
    void shouldOnlyProcessedMessageOnceForConcurrentConsumers() {
        EventEntity eventEntity = createTestEventEntity();

        eventRepository.save(eventEntity);
        // DETACHED

        Message message = initializeEventCreateTestMessage(eventEntity, CREATE_EVENT_MESSAGETYPE);

        CountDownLatch countDownLatch = new CountDownLatch(2);

        try (
                ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        ) {
            CompletableFuture<Void> result1 = consumeMessageAsync(message, countDownLatch, executorService);
            CompletableFuture<Void> result2 = consumeMessageAsync(message, countDownLatch, executorService);

            result1.join();
            result2.join();
        }

        int seatCount = seatRepository.countByEvent_EventIdAndSeatStatus(eventEntity.getEventId(), SeatStatus.AVAILABLE);

        long processedMessageCount = processedMessageRepository.count();

        Assertions.assertThat(seatCount).isEqualTo(eventEntity.getPlacesNumber());
        Assertions.assertThat(processedMessageCount).isEqualTo(1);
    }

    private CompletableFuture<CorrelationData.Confirm> verifySentMessageAsync(CorrelationData correlationData) {
        return correlationData.getFuture().whenComplete((confirm, throwable) -> {
            ReturnedMessage returned = correlationData.getReturned();

            if (throwable != null)
                Assertions.fail("FATAL ERROR in communication PUBLISHER <-> BROKER \n" + throwable.getMessage());
            if (returned != null)
                Assertions.fail("Cannot successfully route message to queue=" + createEventQueueName);
            if (!confirm.ack())
                Assertions.fail("Cannot successfully deliver message to Broker");
        });
    }

    @Test
    void shouldSuccessfullyConsumeMessageFromCreateEventQueue() {
        EventEntity eventEntity = createTestEventEntity();

        eventRepository.save(eventEntity);
        // DETACHED

        Message message = initializeEventCreateTestMessage(eventEntity, CREATE_EVENT_MESSAGETYPE);
        String messageId = message.getMessageProperties().getMessageId();

        CorrelationData correlationData = new CorrelationData(String.valueOf(messageId));
        rabbitTemplate.send(mainExchangeName, createEventRoutingKey, message, correlationData);

        CompletableFuture<CorrelationData.Confirm> result = verifySentMessageAsync(correlationData);
        result.join();

        int seatCount = 0;
        int deadline = 0;
        while ((seatCount = seatRepository.countByEvent_EventIdAndSeatStatus(eventEntity.getEventId(), SeatStatus.AVAILABLE)) != eventEntity.getPlacesNumber() &&
                deadline < 20) {
            try {
                Thread.sleep(Duration.ofMillis(500));
                deadline++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Assertions.fail("Main Thread has been interrupted");
            }
        }

        long processedMessageCount = processedMessageRepository.count();

        Assertions.assertThat(seatCount).isEqualTo(eventEntity.getPlacesNumber());
        Assertions.assertThat(processedMessageCount).isEqualTo(1);
    }


    @Test
    void shouldDeadLetterMessageWhenMessageTypeIsInvalid() {
        EventEntity eventEntity = createTestEventEntity();

        eventRepository.save(eventEntity);
        // DETACHED

        Message message = initializeEventCreateTestMessage(eventEntity, new MessageType(AggregateType.RESERVATION, OperationType.DELETE, MessagePayloadVersion.V1));
        String messageId = message.getMessageProperties().getMessageId();

        CorrelationData correlationData = new CorrelationData(String.valueOf(messageId));
        rabbitTemplate.send(mainExchangeName, createEventRoutingKey, message, correlationData);

        CompletableFuture<CorrelationData.Confirm> result = verifySentMessageAsync(correlationData);
        result.join();

        // Receive message from DLQ
        Message received = rabbitTemplate.receive(createEventDLQ, Duration.ofSeconds(10).toMillis());

        int seatCount = seatRepository.countByEvent_EventIdAndSeatStatus(eventEntity.getEventId(), SeatStatus.AVAILABLE);
        long processedMessageCount = processedMessageRepository.count();

        Assertions.assertThat(seatCount).isEqualTo(0);
        Assertions.assertThat(processedMessageCount).isEqualTo(0);
        Assertions.assertThat(received).isNotNull();
        Assertions.assertThat(received.getMessageProperties().getMessageId()).isEqualTo(messageId);

    }

    @Test
    void shouldDeadLetterMessageWhenPayloadIsInvalid() {
        EventEntity eventEntity = createTestEventEntity();

        eventRepository.save(eventEntity);
        // DETACHED

        String invalidPayload = "InvalidPayload";
        Message message = initializeEventCreateTestMessage(eventEntity, CREATE_EVENT_MESSAGETYPE, invalidPayload);
        String messageId = message.getMessageProperties().getMessageId();

        CorrelationData correlationData = new CorrelationData(String.valueOf(messageId));
        rabbitTemplate.send(mainExchangeName, createEventRoutingKey, message, correlationData);

        CompletableFuture<CorrelationData.Confirm> result = verifySentMessageAsync(correlationData);
        result.join();

        // Receive message from DLQ
        Message received = rabbitTemplate.receive(createEventDLQ, Duration.ofSeconds(10).toMillis());

        int seatCount = seatRepository.countByEvent_EventIdAndSeatStatus(eventEntity.getEventId(), SeatStatus.AVAILABLE);
        long processedMessageCount = processedMessageRepository.count();

        Assertions.assertThat(seatCount).isEqualTo(0);
        Assertions.assertThat(processedMessageCount).isEqualTo(0);
        Assertions.assertThat(received).isNotNull();
        Assertions.assertThat(received.getMessageProperties().getMessageId()).isEqualTo(messageId);
    }

    @Test
    void shouldDeadLetterMessageAfterRetryExhaustion() {
        Long notExistingEventID = 1L;

        boolean exists = eventRepository.existsById(notExistingEventID);
        Assertions.assertThat(exists).isFalse();

        CreateEventMessagePayloadDto payloadDto = new CreateEventMessagePayloadDto(notExistingEventID, 5_000, 50, "event-test");

        Message message = initializeEventCreateTestMessage(payloadDto, CREATE_EVENT_MESSAGETYPE);
        String messageId = message.getMessageProperties().getMessageId();

        CorrelationData correlationData = new CorrelationData(String.valueOf(messageId));
        rabbitTemplate.send(mainExchangeName, createEventRoutingKey, message, correlationData);

        CompletableFuture<CorrelationData.Confirm> result = verifySentMessageAsync(correlationData);
        result.join();

        // Receive message from DLQ
        // total backoff = 6s
        Message received = rabbitTemplate.receive(createEventDLQ, Duration.ofSeconds(10).toMillis());

        int seatCount = seatRepository.countByEvent_EventIdAndSeatStatus(payloadDto.eventId(), SeatStatus.AVAILABLE);
        long processedMessageCount = processedMessageRepository.count();

        Assertions.assertThat(seatCount).isEqualTo(0);
        Assertions.assertThat(processedMessageCount).isEqualTo(0);
        Assertions.assertThat(received).isNotNull();
        Assertions.assertThat(received.getMessageProperties().getMessageId()).isEqualTo(messageId);
    }
}

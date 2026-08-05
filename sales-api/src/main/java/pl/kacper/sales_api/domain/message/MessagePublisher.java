package pl.kacper.sales_api.domain.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import pl.kacper.sales_api.common.exception.ConcurrencyClaimMessageException;
import pl.kacper.sales_api.domain.message.property.MessageStatus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Component
public class MessagePublisher {

    private final OutboxMessageTransactionService outboxMessageTransactionService;
    private final RabbitTemplate rabbitTemplate;

    @Value("${database.fetch-size}")
    private int fetchSize;
    @Value("${messaging-scheduler.batches-processing-at-once}")
    private int batchesAtOnce;

    private static final int MAX_RETRY_AFTER_INITIAL = 5;
    private static final Duration[] RETRY_DELAYS = {Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(180), Duration.ofSeconds(500)};
    private static final Duration LOCKED_TIMEOUT_DURATION = Duration.ofMinutes(5);

    private static final Logger LOGGER = LoggerFactory.getLogger(MessagePublisher.class);


    private static final int MAX_WAITING_CONFIRMS = 100;
    private final Semaphore semaphore = new Semaphore(MAX_WAITING_CONFIRMS);

    @Autowired
    public MessagePublisher(OutboxMessageTransactionService outboxMessageTransactionService, RabbitTemplate rabbitTemplate) {
        this.outboxMessageTransactionService = outboxMessageTransactionService;
        this.rabbitTemplate = rabbitTemplate;
    }

    private Instant calculateNextAttemptTime(int retryCount) {
        return Instant.ofEpochMilli(Instant.now().toEpochMilli() + RETRY_DELAYS[retryCount].toMillis());
    }

    public void scanAndPublishMessage() {
        Collection<OutboxMessageEntity> stuckEntities;
        do {
            stuckEntities = outboxMessageTransactionService.requeueProcessingMessages(
                    Instant.ofEpochMilli(Instant.now().toEpochMilli() - LOCKED_TIMEOUT_DURATION.toMillis()),
                    fetchSize);
        } while (!stuckEntities.isEmpty());

        Collection<OutboxMessageEntity> outboxMessageEntities;
        int batchesCounter = 0;
        do {
            int reservedPermits = Math.min(semaphore.availablePermits(), fetchSize);
            if (reservedPermits == 0) break; // Terminate loop scanning
            boolean flag = semaphore.tryAcquire(reservedPermits);
            if (flag) {
                boolean permitsAssigned = false;
                try {
                    outboxMessageEntities = outboxMessageTransactionService.scanAndMarkProcessing(reservedPermits);  // Locking rows
                    if (outboxMessageEntities.isEmpty())
                        break; // Terminate loop scanning

                    semaphore.release(reservedPermits - outboxMessageEntities.size());
                    permitsAssigned = true;
                } finally {
                    if (!permitsAssigned)
                        semaphore.release(reservedPermits);
                }
            } else break; // Terminate loop scanning

            // outboxMessageEntities = DETACHED ENTITIES

            for (OutboxMessageEntity outboxMessageEntity : outboxMessageEntities) {
                Message message = createMessage(outboxMessageEntity);
                sendMessageAsync(outboxMessageEntity, message);
            }

            batchesCounter++;

        } while (batchesCounter < batchesAtOnce);
    }

    // Intermediate use for new created message
    public void trySendSingleMessageAsync(UUID messageId) {
        boolean flag = semaphore.tryAcquire();

        OutboxMessageEntity outboxMessageEntity;
        if (flag) {
            boolean permitsAssigned = false;
            try {
                outboxMessageEntity = outboxMessageTransactionService.markPendingMessageAsProcessing(messageId); // Locking single row
                permitsAssigned = true;
            } catch (CannotAcquireLockException e) {
                LOGGER.debug(
                        "Immediate publishing skipped because message [ID={}] is currently claimed by another publisher",
                        messageId
                );
                return;
            } catch (ConcurrencyClaimMessageException e) {
                LOGGER.debug("""
                        Immediate send message[ID={}] has been skipped, because message is already claim by other process  \n
                        ConcurrencyClaimMessageException: {}
                        """, messageId, e.getMessage());
                return;
            } finally {
                if (!permitsAssigned)
                    semaphore.release();
            }
        } else return;

        Message message = createMessage(outboxMessageEntity);
        sendMessageAsync(outboxMessageEntity, message);
    }

    private Message createMessage(OutboxMessageEntity outboxMessageEntity) {
        MessageProperties messageProperties = MessagePropertiesBuilder.newInstance()
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(String.valueOf(outboxMessageEntity.getMessageId()))
                .setContentType("application/json")
                .setContentEncoding("UTF-8")
                .setHeader("aggregateType", outboxMessageEntity.getAggregateType().name())
                .setHeader("aggregateId", outboxMessageEntity.getAggregateId())
                .setHeader("operationType", outboxMessageEntity.getOperationType().name())
                .setHeader("payloadVersion", outboxMessageEntity.getPayloadVersion().name())
                .build();

        return MessageBuilder.withBody(outboxMessageEntity.getPayload().getBytes(StandardCharsets.UTF_8)).andProperties(messageProperties).build();
    }

    private void sendMessageAsync(OutboxMessageEntity outboxMessageEntity, Message message) {
        String exchange = outboxMessageEntity.getExchange();
        String routingKey = outboxMessageEntity.getRoutingKey();
        CorrelationData correlationData = new CorrelationData(String.valueOf(outboxMessageEntity.getMessageId()));

        try {
            rabbitTemplate.send(exchange, routingKey, message, correlationData);
            correlationData.getFuture().whenComplete((confirm, throwable) -> {
                try {
                    ReturnedMessage returned = correlationData.getReturned();

                    if (throwable != null) {
                        failedHandler(outboxMessageEntity, throwable.getMessage());
                        LOGGER.error("Future message exception", throwable);
                    } else if (returned != null) {
                        // Routing failure = Status.FAILED
                        outboxMessageEntity.setMessageStatus(MessageStatus.FAILED);
                        String ex = returned.getExchange();
                        String rk = returned.getRoutingKey();
                        LOGGER.error(
                                """
                                        Routing message failure: {}
                                        Exchange set: {}
                                        Routing key set: {}
                                        """, returned.getMessage(), ex, rk
                        );

                    } else if (confirm.ack()) {
                        outboxMessageEntity.setMessageStatus(MessageStatus.SENT);
                        outboxMessageEntity.setSentAt(Instant.now());
                    } else
                        failedHandler(outboxMessageEntity, confirm.reason());

                    outboxMessageEntity.setLockedAt(null);
                    updateRecord(outboxMessageEntity); // transaction
                } finally {
                    semaphore.release(); // Release after persist or failed-persist transaction
                }
            });
        } catch (AmqpException e) {
            try {
                failedHandler(outboxMessageEntity, e.getMessage());
                outboxMessageEntity.setLockedAt(null);
                updateRecord(outboxMessageEntity);
                LOGGER.error(
                        """
                                Error message: {}
                                Message: messageId={}, retryCount={}, nextAttemptAt={}
                                """, e.getMessage(), outboxMessageEntity.getMessageId(), outboxMessageEntity.getRetryCount(), outboxMessageEntity.getNextAttemptAt(), e
                );
            } finally {
                semaphore.release(); // release when AMQP exception
            }
        }
    }

    private void failedHandler(OutboxMessageEntity outboxMessageEntity, String reason) {
        if (outboxMessageEntity.getRetryCount() + 1 > MAX_RETRY_AFTER_INITIAL) {
            outboxMessageEntity.setMessageStatus(MessageStatus.FAILED);
            LOGGER.warn(
                    """
                            Message rejected
                            Message [messageId={}]
                            Rejected reason: {}
                            ### Out of retries: Message will be marked as FAILED
                            """, outboxMessageEntity.getMessageId(), reason
            );
        } else {
            outboxMessageEntity.setMessageStatus(MessageStatus.PENDING);
            outboxMessageEntity.setNextAttemptAt(calculateNextAttemptTime(outboxMessageEntity.getRetryCount()));
            outboxMessageEntity.setRetryCount(outboxMessageEntity.getRetryCount() + 1);
            LOGGER.warn(
                    """
                            Message rejected
                            Message [messageId={}]
                            Rejected reason: {}
                            Next attempt: {}
                            """, outboxMessageEntity.getMessageId(), reason, outboxMessageEntity.getNextAttemptAt()
            );
        }
    }

    private void updateRecord(OutboxMessageEntity outboxMessageEntity) {
        try {
            outboxMessageTransactionService.update(outboxMessageEntity); // SEPARATE TRANSACTION UPDATE RECORD - SHORT TRANSACTION
        } catch (OptimisticLockingFailureException e) {
            LOGGER.error(
                    """
                            Error message: Message update failure. Message has been changed by other Thread,
                            NOT Updated state: messageId={}, messageStatus={}
                            """, outboxMessageEntity.getMessageId(), outboxMessageEntity.getMessageStatus(), e
            );
        }
    }
}
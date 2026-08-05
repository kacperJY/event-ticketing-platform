package pl.kacper.sales_api.domain.event;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import pl.kacper.sales_api.domain.BaseIT;
import pl.kacper.sales_api.domain.event.dto.Address;
import pl.kacper.sales_api.domain.event.dto.CreateEventRequestDto;
import pl.kacper.sales_api.domain.message.OutboxMessageRepository;
import pl.kacper.sales_api.domain.message.OutboxMessageTransactionService;
import pl.kacper.sales_api.domain.message.dto.EntityAndMessageDto;
import pl.kacper.sales_api.domain.message.property.MessageStatus;
import pl.kacper.sales_api.utils.TransactionTestUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

class MessageConcurrentIT extends BaseIT {

    private final EventTransactionService eventTransactionService;
    private final OutboxMessageTransactionService outboxMessageTransactionService;
    private final TransactionTestUtil transactionTestUtil;
    private final OutboxMessageRepository outboxMessageRepository;

    @Autowired
    public MessageConcurrentIT(EventTransactionService eventTransactionService, OutboxMessageTransactionService outboxMessageTransactionService, TransactionTestUtil transactionTestUtil, OutboxMessageRepository outboxMessageRepository) {
        this.eventTransactionService = eventTransactionService;
        this.outboxMessageTransactionService = outboxMessageTransactionService;
        this.transactionTestUtil = transactionTestUtil;
        this.outboxMessageRepository = outboxMessageRepository;
    }

    @Test
    void shouldThrowCannotAcquireLockExceptionWhenAnotherTransactionHoldsMessageLock() {
        CreateEventRequestDto createEventRequestDto = new CreateEventRequestDto(
                "test-event",
                "test-description",
                EventCategory.CINEMA,
                new Address("Poland", "Test-City", "Test-Street", "Test-Address", "00-000"),
                50_000L,
                Instant.now().plus(Duration.ofDays(4)),
                50
        );
        EntityAndMessageDto<Long> longEntityAndMessageDto = eventTransactionService.saveEventAndMessage(createEventRequestDto);

        UUID messageID = longEntityAndMessageDto.messageID();

        CountDownLatch acquireLock = new CountDownLatch(2);
        CountDownLatch releaseLock = new CountDownLatch(1);

        try (
                ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        ) {

            CompletableFuture<Void> result1 = CompletableFuture.runAsync(() -> {
                transactionTestUtil.beginAndHoldTransaction(
                        () -> outboxMessageRepository.findByStatusAndIDWithLockingNoWait(messageID, MessageStatus.PENDING).orElseThrow(() -> new AssertionError("Pending outbox message was not found")),
                        acquireLock,
                        releaseLock
                );
            }, executorService);

            CompletableFuture<Void> result2 = CompletableFuture.runAsync(() -> {
                try {
                    acquireLock.countDown();

                    boolean await = acquireLock.await(5L, TimeUnit.SECONDS);

                    if (!await) Assertions.fail("Test infrastructure fail");

                    outboxMessageTransactionService.markPendingMessageAsProcessing(messageID);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assertions.fail("Task Thread has been interrupted");
                } finally {
                    releaseLock.countDown();
                }
            }, executorService);

            int operationExceptionCounter = 0;

            try {
                result1.join();
            } catch (CompletionException primaryEx) {
                Assertions.fail(primaryEx.getCause().getMessage());
            }
            try {
                result2.join();
            } catch (CompletionException primaryEx) {
                Throwable caused = primaryEx.getCause();
                Assertions.assertThat(caused).isInstanceOf(CannotAcquireLockException.class);
                operationExceptionCounter++;
            }
            Assertions.assertThat(operationExceptionCounter).isEqualTo(1);
        }
    }

}

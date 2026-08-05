package pl.kacper.sales_api.domain.message;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.CannotAcquireLockException;

import java.util.UUID;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
class MessagePublisherTest {

    @Mock
    private OutboxMessageTransactionService outboxMessageTransactionService;
    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private MessagePublisher messagePublisher;

    @Test
    void shouldEndSuccessfullyWhenLockingExceptionIsThrown(){
        UUID messageId = UUID.randomUUID();

        Mockito.when(outboxMessageTransactionService.markPendingMessageAsProcessing(messageId)).thenThrow(CannotAcquireLockException.class);

        messagePublisher.trySendSingleMessageAsync(messageId);

        Mockito.verify(rabbitTemplate,Mockito.never())
                .send(ArgumentMatchers.anyString(),ArgumentMatchers.anyString(),ArgumentMatchers.any(),ArgumentMatchers.any());
    }
}

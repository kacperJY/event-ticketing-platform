package pl.kacper.fulfillment_worker.domain.order;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.test.util.ReflectionTestUtils;
import pl.kacper.exception.InvalidMessageFormatException;
import pl.kacper.fulfillment_worker.domain.message.CompletedOrderMessageResolver;
import pl.kacper.fulfillment_worker.domain.message.dto.payload.CompletedOrderMessagePayloadV1;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
class CompletedOrderMessageConsumerTest {

    @Mock
    private CompletedOrderMessageTransactionService completedOrderMessageTransactionService;

    @Mock
    private CompletedOrderMessageResolver completedOrderMessageResolver;

    @Mock
    private CompletedOrderMessagePayloadValidator completedOrderMessagePayloadValidator;

    @InjectMocks
    private CompletedOrderMessageConsumer completedOrderMessageConsumer;

    private static final UUID ORDER_ID = UUID.randomUUID();

    private final CompletedOrderMessagePayloadV1 completedOrderMessagePayloadV1 = new CompletedOrderMessagePayloadV1(
            ORDER_ID,
            "mail@gmail.com",
            Instant.now(),
            List.of(new CompletedOrderMessagePayloadV1.Item(
                    30195L,
                    "Global AI & Tech Expo",
                    "United States",
                    "San Francisco",
                    "Howard Street",
                    "747",
                    "94103",
                    Instant.now(),
                    "Conferences",
                    "Main Hall, Desk 104"
            ))
    );

    @Test
    void shouldSuccessfullyConsumeCorrectMessage() {

        UUID messageId = UUID.randomUUID();
        String consumerName = "Test-Consumer";

        Message message = new Message("".getBytes());

        ReflectionTestUtils.setField(completedOrderMessageConsumer, "consumerName", consumerName);

        Mockito.when(completedOrderMessageResolver.resolveMessage(message)).thenReturn(
                new CompletedOrderMessageResolver.ResolvedPayloadByMessageId(messageId, completedOrderMessagePayloadV1)
        );

        completedOrderMessageConsumer.consumeMessage(message);

        Mockito.verify(completedOrderMessageResolver, Mockito.only()).resolveMessage(message);
        Mockito.verify(completedOrderMessagePayloadValidator, Mockito.only()).validate(completedOrderMessagePayloadV1);
        Mockito.verify(completedOrderMessageTransactionService, Mockito.only()).processMessage(consumerName, messageId, completedOrderMessagePayloadV1);
    }

    @Test
    void shouldThrowExceptionOnMessageResolving() {
        String consumerName = "Test-Consumer";

        Message message = new Message("".getBytes());
        ReflectionTestUtils.setField(completedOrderMessageConsumer, "consumerName", consumerName);

        Mockito.when(completedOrderMessageResolver.resolveMessage(message)).thenThrow(InvalidMessageFormatException.class);

        Assertions.assertThatThrownBy(() -> completedOrderMessageConsumer.consumeMessage(message))
                .isInstanceOf(InvalidMessageFormatException.class);

        Mockito.verify(completedOrderMessageResolver, Mockito.only()).resolveMessage(message);
        Mockito.verify(completedOrderMessagePayloadValidator, Mockito.times(0)).validate(ArgumentMatchers.any());
        Mockito.verify(completedOrderMessageTransactionService, Mockito.times(0)).processMessage(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void shouldThrowExceptionOnValidatingDto() {
        UUID messageId = UUID.randomUUID();
        String consumerName = "Test-Consumer";

        Message message = new Message("".getBytes());

        ReflectionTestUtils.setField(completedOrderMessageConsumer, "consumerName", consumerName);

        Mockito.when(completedOrderMessageResolver.resolveMessage(message)).thenReturn(
                new CompletedOrderMessageResolver.ResolvedPayloadByMessageId(messageId, completedOrderMessagePayloadV1)
        );

        Mockito.doThrow(InvalidMessageFormatException.class).when(completedOrderMessagePayloadValidator).validate(completedOrderMessagePayloadV1);

        Assertions.assertThatThrownBy(() -> completedOrderMessageConsumer.consumeMessage(message))
                .isInstanceOf(InvalidMessageFormatException.class);

        Mockito.verify(completedOrderMessageResolver, Mockito.only()).resolveMessage(message);
        Mockito.verify(completedOrderMessagePayloadValidator, Mockito.only()).validate(completedOrderMessagePayloadV1);
        Mockito.verify(completedOrderMessageTransactionService, Mockito.times(0)).processMessage(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void shouldThrowExceptionOnProcessMessage() {
        UUID messageId = UUID.randomUUID();
        String consumerName = "Test-Consumer";

        Message message = new Message("".getBytes());

        ReflectionTestUtils.setField(completedOrderMessageConsumer, "consumerName", consumerName);

        Mockito.when(completedOrderMessageResolver.resolveMessage(message)).thenReturn(
                new CompletedOrderMessageResolver.ResolvedPayloadByMessageId(messageId, completedOrderMessagePayloadV1)
        );

        Mockito.doThrow(RuntimeException.class).when(completedOrderMessageTransactionService).processMessage(consumerName, messageId, completedOrderMessagePayloadV1);

        Assertions.assertThatThrownBy(() -> completedOrderMessageConsumer.consumeMessage(message))
                .isInstanceOf(RuntimeException.class);

        Mockito.verify(completedOrderMessageResolver, Mockito.only()).resolveMessage(message);
        Mockito.verify(completedOrderMessagePayloadValidator, Mockito.only()).validate(completedOrderMessagePayloadV1);
        Mockito.verify(completedOrderMessageTransactionService, Mockito.only()).processMessage(consumerName, messageId, completedOrderMessagePayloadV1);
    }
}

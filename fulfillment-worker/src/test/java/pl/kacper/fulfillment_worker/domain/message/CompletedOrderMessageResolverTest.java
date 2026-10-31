package pl.kacper.fulfillment_worker.domain.message;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import pl.kacper.exception.InvalidMessageFormatException;
import pl.kacper.exception.RoutingException;
import pl.kacper.fulfillment_worker.domain.message.dto.payload.CompletedOrderMessagePayloadV1;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompletedOrderMessageResolverTest {

    private static final String OPERATION_TYPE_HEADER_NAME = "operationType";
    private static final String AGGREGATE_TYPE_HEADER_NAME = "aggregateType";
    private static final String AGGREGATE_ID_HEADER_NAME = "aggregateId";
    private static final String PAYLOAD_VERSION_HEADER_NAME = "payloadVersion";

    private static final String OPERATION_TYPE_CONTRACT_VALUE = "PAID";
    private static final String AGGREGATE_TYPE_CONTRACT_VALUE = "ORDER";
    private static final String PAYLOAD_VERSION_CONTRACT_VALUE = "V1";

    private static final String CONTENT_TYPE = "application/json";
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID MESSAGE_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final Instant DATE = Instant.now();

    private final CompletedOrderMessageResolver completedOrderMessageResolver = new CompletedOrderMessageResolver(
            OPERATION_TYPE_HEADER_NAME,
            AGGREGATE_TYPE_HEADER_NAME,
            PAYLOAD_VERSION_HEADER_NAME,
            OPERATION_TYPE_CONTRACT_VALUE,
            AGGREGATE_TYPE_CONTRACT_VALUE,
            PAYLOAD_VERSION_CONTRACT_VALUE,
            AGGREGATE_ID_HEADER_NAME
    );

    private static final CompletedOrderMessagePayloadV1 VALID_PAYLOAD = new CompletedOrderMessagePayloadV1(
            ORDER_ID,
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
                            "HA9 0WS",
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
                .setHeader(AGGREGATE_TYPE_HEADER_NAME, aggregateType)
                .setHeader(AGGREGATE_ID_HEADER_NAME, aggregateId)
                .setHeader(OPERATION_TYPE_HEADER_NAME, operationType)
                .setHeader(PAYLOAD_VERSION_HEADER_NAME, payloadVersion)
                .build();
    }

    private Message buildValidMessage() {
        return buildMessageWithBody(
                MESSAGE_ID.toString(),
                OPERATION_TYPE_CONTRACT_VALUE,
                AGGREGATE_TYPE_CONTRACT_VALUE,
                ORDER_ID.toString(),
                PAYLOAD_VERSION_CONTRACT_VALUE,
                VALID_PAYLOAD
        );
    }

    @Test
    void shouldSuccessfullyResolveMessage() {
        Message message = buildValidMessage();

        CompletedOrderMessageResolver.ResolvedPayloadByMessageId result = completedOrderMessageResolver.resolveMessage(message);

        Assertions.assertThat(result.messageId()).isEqualTo(MESSAGE_ID);
        Assertions.assertThat(result.completedOrderMessagePayloadV1()).isEqualTo(VALID_PAYLOAD);
    }

    @Test
    void shouldRejectNullMessage() {
        Assertions.assertThatThrownBy(() -> completedOrderMessageResolver.resolveMessage(null))
                .isInstanceOf(InvalidMessageFormatException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "invalid-message-id"})
    void shouldRejectInvalidMessageId(String messageId) {
        Message message = buildMessageWithBody(
                messageId,
                OPERATION_TYPE_CONTRACT_VALUE,
                AGGREGATE_TYPE_CONTRACT_VALUE,
                ORDER_ID.toString(),
                PAYLOAD_VERSION_CONTRACT_VALUE,
                VALID_PAYLOAD
        );

        Assertions.assertThatThrownBy(() -> completedOrderMessageResolver.resolveMessage(message))
                .isInstanceOf(InvalidMessageFormatException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            OPERATION_TYPE_HEADER_NAME,
            AGGREGATE_TYPE_HEADER_NAME,
            AGGREGATE_ID_HEADER_NAME,
            PAYLOAD_VERSION_HEADER_NAME
    })
    void shouldRejectMessageWithMissingRequiredHeader(String headerName) {
        Message message = buildValidMessage();
        message.getMessageProperties().getHeaders().remove(headerName);

        Assertions.assertThatThrownBy(() -> completedOrderMessageResolver.resolveMessage(message))
                .isInstanceOf(InvalidMessageFormatException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            OPERATION_TYPE_HEADER_NAME,
            AGGREGATE_TYPE_HEADER_NAME,
            AGGREGATE_ID_HEADER_NAME,
            PAYLOAD_VERSION_HEADER_NAME
    })
    void shouldRejectMessageWithInvalidRequiredHeaderType(String headerName) {
        Message message = buildValidMessage();
        message.getMessageProperties().setHeader(headerName, 1L);

        Assertions.assertThatThrownBy(() -> completedOrderMessageResolver.resolveMessage(message))
                .isInstanceOf(InvalidMessageFormatException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            OPERATION_TYPE_HEADER_NAME,
            AGGREGATE_TYPE_HEADER_NAME,
            PAYLOAD_VERSION_HEADER_NAME
    })
    void shouldRejectMessageWithInvalidContractHeaderValue(String headerName) {
        Message message = buildValidMessage();
        message.getMessageProperties().setHeader(headerName, "INVALID");

        Assertions.assertThatThrownBy(() -> completedOrderMessageResolver.resolveMessage(message))
                .isInstanceOf(RoutingException.class);
    }

    @Test
    void shouldRejectInvalidAggregateId() {
        Message message = buildMessageWithBody(
                MESSAGE_ID.toString(),
                OPERATION_TYPE_CONTRACT_VALUE,
                AGGREGATE_TYPE_CONTRACT_VALUE,
                "invalid-aggregate-id",
                PAYLOAD_VERSION_CONTRACT_VALUE,
                VALID_PAYLOAD
        );

        Assertions.assertThatThrownBy(() -> completedOrderMessageResolver.resolveMessage(message))
                .isInstanceOf(InvalidMessageFormatException.class);
    }

    @Test
    void shouldRejectUnsupportedContentType() {
        Message message = buildMessage(
                MESSAGE_ID.toString(),
                "text/plain",
                OPERATION_TYPE_CONTRACT_VALUE,
                AGGREGATE_TYPE_CONTRACT_VALUE,
                ORDER_ID.toString(),
                PAYLOAD_VERSION_CONTRACT_VALUE,
                objectMapper.writeValueAsBytes(VALID_PAYLOAD)
        );

        Assertions.assertThatThrownBy(() -> completedOrderMessageResolver.resolveMessage(message))
                .isInstanceOf(InvalidMessageFormatException.class);
    }

    @Test
    void shouldRejectEmptyContentType() {
        Message message = buildMessage(
                MESSAGE_ID.toString(),
                null,
                OPERATION_TYPE_CONTRACT_VALUE,
                AGGREGATE_TYPE_CONTRACT_VALUE,
                ORDER_ID.toString(),
                PAYLOAD_VERSION_CONTRACT_VALUE,
                objectMapper.writeValueAsBytes(VALID_PAYLOAD)
        );

        Assertions.assertThatThrownBy(() -> completedOrderMessageResolver.resolveMessage(message))
                .isInstanceOf(InvalidMessageFormatException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidBodyCases")
    void shouldRejectInvalidBody(byte[] body) {
        Message message = buildMessage(
                MESSAGE_ID.toString(),
                CONTENT_TYPE,
                OPERATION_TYPE_CONTRACT_VALUE,
                AGGREGATE_TYPE_CONTRACT_VALUE,
                ORDER_ID.toString(),
                PAYLOAD_VERSION_CONTRACT_VALUE,
                body
        );

        Assertions.assertThatThrownBy(() -> completedOrderMessageResolver.resolveMessage(message))
                .isInstanceOf(InvalidMessageFormatException.class);
    }

    Stream<Arguments> invalidBodyCases() {
        return Stream.of(
                Arguments.of((Object) new byte[0]),
                Arguments.of((Object) "{".getBytes(DEFAULT_CHARSET)),
                Arguments.of((Object) "null".getBytes(DEFAULT_CHARSET))
        );
    }

    @Test
    void shouldRejectAggregateIdDifferentFromPayloadOrderId() {
        Message message = buildMessageWithBody(
                MESSAGE_ID.toString(),
                OPERATION_TYPE_CONTRACT_VALUE,
                AGGREGATE_TYPE_CONTRACT_VALUE,
                UUID.randomUUID().toString(),
                PAYLOAD_VERSION_CONTRACT_VALUE,
                VALID_PAYLOAD
        );

        Assertions.assertThatThrownBy(() -> completedOrderMessageResolver.resolveMessage(message))
                .isInstanceOf(InvalidMessageFormatException.class);
    }
}

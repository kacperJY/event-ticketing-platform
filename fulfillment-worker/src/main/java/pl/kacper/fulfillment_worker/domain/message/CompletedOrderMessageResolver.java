package pl.kacper.fulfillment_worker.domain.message;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pl.kacper.exception.InvalidMessageFormatException;
import pl.kacper.exception.RoutingException;
import pl.kacper.fulfillment_worker.domain.message.dto.MessageHeaderValue;
import pl.kacper.fulfillment_worker.domain.message.dto.payload.CompletedOrderMessagePayloadV1;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;


@Component
public class CompletedOrderMessageResolver {

    private static final String SUPPORTED_CONTENT_TYPE = "application/json";

    // CONSTANT CONTRACT HEADERS
    private final MessageHeaderValue operationTypeHeaderContract;
    private final MessageHeaderValue aggregateTypeHeaderContract;
    private final MessageHeaderValue messagePayloadVersionHeaderContract;

    // HEADERS PER MESSAGE
    private final String aggregateIdHeaderName;

    private final ObjectMapper objectMapper;

    @Autowired
    CompletedOrderMessageResolver(
            @Value("${message-integration.sales-api.headers.operation-type}") String operationTypeHeaderName,
            @Value("${message-integration.sales-api.headers.aggregate-type}") String aggregateTypeHeaderName,
            @Value("${message-integration.sales-api.headers.message-payload-version}") String messagePayloadVersionHeaderName,

            @Value("${message-integration.sales-api.contract.completed-order.operation-type}") String operationTypeContractValue,
            @Value("${message-integration.sales-api.contract.completed-order.aggregate-type}") String aggregateTypeContractValue,
            @Value("${message-integration.sales-api.contract.completed-order.message-payload-version}") String messagePayloadVersionContractValue,

            @Value("${message-integration.sales-api.headers.aggregate-id}") String aggregateIdHeaderName
    ) {
        this.operationTypeHeaderContract = new MessageHeaderValue(operationTypeHeaderName, operationTypeContractValue);
        this.aggregateTypeHeaderContract = new MessageHeaderValue(aggregateTypeHeaderName, aggregateTypeContractValue);
        this.messagePayloadVersionHeaderContract = new MessageHeaderValue(messagePayloadVersionHeaderName, messagePayloadVersionContractValue);

        this.aggregateIdHeaderName = aggregateIdHeaderName;
        this.objectMapper = new ObjectMapper();
    }

    public ResolvedPayloadByMessageId resolveMessage(Message message) {
        UUID messageId = validateMessageAndBuildMessageID(message);

        String operationType = readHeaderAsString(message, operationTypeHeaderContract.header());
        String aggregateType = readHeaderAsString(message, aggregateTypeHeaderContract.header());
        String messagePayloadVersion = readHeaderAsString(message, messagePayloadVersionHeaderContract.header());
        String textOrderAggregateId = readHeaderAsString(message, aggregateIdHeaderName);

        if (operationType == null || aggregateType == null || textOrderAggregateId == null || messagePayloadVersion == null)
            throw new InvalidMessageFormatException("""
                    Cannot resolve message because there is at least one empty header:
                    AggregateType = %s
                    OperationType = %s
                    PayloadVersion = %s
                    AggregateId = %s
                    """.formatted(aggregateType, operationType, messagePayloadVersion, textOrderAggregateId));

        UUID orderAggregateId;
        try {
            orderAggregateId = UUID.fromString(textOrderAggregateId);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageFormatException("Cannot resolve message because it has invalid aggregateId=%s".formatted(textOrderAggregateId));
        }

        validateContract(operationType, aggregateType, messagePayloadVersion);

        CompletedOrderMessagePayloadV1 completedOrderMessagePayloadV1 = validateContentTypeAndDeserialize(message);

        if (completedOrderMessagePayloadV1 == null)
            throw new InvalidMessageFormatException("Cannot resolve message with payload equal to null");

        if (!orderAggregateId.equals(completedOrderMessagePayloadV1.orderId()))
            throw new InvalidMessageFormatException(
                    """
                            Cannot resolve message because aggregateId does not match payload orderId
                            AggregateId: %s
                            OrderId: %s
                            """.formatted(orderAggregateId, completedOrderMessagePayloadV1.orderId())
            );

        return new ResolvedPayloadByMessageId(
                messageId,
                completedOrderMessagePayloadV1
        );
    }

    public record ResolvedPayloadByMessageId(
            UUID messageId,
            CompletedOrderMessagePayloadV1 completedOrderMessagePayloadV1
    ) {

    }

    private UUID validateMessageAndBuildMessageID(Message message) {
        if (message == null)
            throw new InvalidMessageFormatException("Cannot resolve message because received message is null");

        MessageProperties messageProperties = message.getMessageProperties();
        String textMessageId = messageProperties.getMessageId();

        if (textMessageId == null)
            throw new InvalidMessageFormatException("Cannot resolve message because messageId is null");

        try {
            return UUID.fromString(textMessageId);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageFormatException("Cannot resolve message because it has invalid messageId=%s".formatted(textMessageId));
        }
    }

    private CompletedOrderMessagePayloadV1 validateContentTypeAndDeserialize(Message message) {
        String contentType = message.getMessageProperties().getContentType();
        if (contentType == null || !contentType.equals(SUPPORTED_CONTENT_TYPE)) {
            throw new InvalidMessageFormatException("Cannot deserialize message because it has unsupported Content-type. Expected=%s, provided=%s"
                    .formatted(SUPPORTED_CONTENT_TYPE, contentType));
        }
        try {
            return objectMapper.readValue(message.getBody(), CompletedOrderMessagePayloadV1.class);
        } catch (JacksonException ex) {
            throw new InvalidMessageFormatException("Cannot resolve message's payload", ex);
        }
    }

    private void validateContract(String operationType, String aggregateType , String messagePayloadVersion) {
        if (!operationType.equals(operationTypeHeaderContract.value()))
            throw new RoutingException("Cannot resolve message because OperationType header value is invalid. Expected=%s, provided=%s"
                    .formatted(operationTypeHeaderContract.value(), operationType));
        if (!aggregateType.equals(aggregateTypeHeaderContract.value()))
            throw new RoutingException("Cannot resolve message because AggregateType header value is invalid. Expected=%s, provided=%s"
                    .formatted(aggregateTypeHeaderContract.value(), aggregateType));
        if (!messagePayloadVersion.equals(messagePayloadVersionHeaderContract.value()))
            throw new RoutingException("Cannot resolve message because PayloadVersion header value is invalid. Expected=%s, provided=%s"
                    .formatted(messagePayloadVersionHeaderContract.value(), messagePayloadVersion));
    }

    private String readHeaderAsString(Message message, String headerName) {
        MessageProperties messageProperties = message.getMessageProperties();
        try {
            return messageProperties.getHeader(headerName);
        } catch (ClassCastException ex) {
            throw new InvalidMessageFormatException("Invalid type value in header=%s".formatted(headerName));
        }
    }
}
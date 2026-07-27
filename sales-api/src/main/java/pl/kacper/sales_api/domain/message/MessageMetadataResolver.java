package pl.kacper.sales_api.domain.message;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import pl.kacper.sales_api.common.exception.InvalidMessageFormatException;
import pl.kacper.sales_api.domain.message.dto.MessageType;
import pl.kacper.sales_api.domain.message.property.AggregateType;
import pl.kacper.sales_api.domain.message.property.MessagePayloadVersion;
import pl.kacper.sales_api.domain.message.property.OperationType;

import java.util.UUID;

public class MessageMetadataResolver {


    public static MessageType buildMessageType(Message message) {
        MessageProperties messageProperties = message.getMessageProperties();
        Object operationObj = messageProperties.getHeader("operationType");
        Object aggregateObj = messageProperties.getHeader("aggregateType");
        Object payloadVersionObj = messageProperties.getHeader("payloadVersion");

        if (operationObj == null || aggregateObj == null || payloadVersionObj == null)
            throw new InvalidMessageFormatException("Cannot resolve Message to MessageType probably type metadata dont' exists");

        try {
            AggregateType aggregateType = AggregateType.valueOf(String.valueOf(aggregateObj));
            OperationType operationType = OperationType.valueOf(String.valueOf(operationObj));
            MessagePayloadVersion payloadVersion = MessagePayloadVersion.valueOf(String.valueOf(payloadVersionObj));

            return new MessageType(
                    aggregateType,
                    operationType,
                    payloadVersion
            );
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageFormatException("Cannot resolve types metadata from Message to MessageType probably invalid values");
        }

    }

    public static UUID requireMessageId(Message message) {
        if (message != null && message.getMessageProperties().getMessageId() != null)
            try {
                return UUID.fromString(message.getMessageProperties().getMessageId());
            } catch (IllegalArgumentException e){
                throw new InvalidMessageFormatException("Cannot resolve Message ID. Message ID is not compatible with UUID");
            }
        else
            throw new InvalidMessageFormatException("Cannot identify message because ID is null");
    }
}

package pl.kacper.sales_api.domain.message.dto;

import pl.kacper.sales_api.domain.message.property.AggregateType;
import pl.kacper.sales_api.domain.message.property.MessagePayloadVersion;
import pl.kacper.sales_api.domain.message.property.OperationType;

public record MessageType(
        AggregateType aggregateType,
        OperationType operationType,
        MessagePayloadVersion messagePayloadVersion
) {
}

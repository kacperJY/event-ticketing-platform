package pl.kacper.sales_api.domain.message;

import jakarta.persistence.Embeddable;

import java.util.UUID;

@Embeddable
public record ProcessedMessageId(
        String consumerName,
        UUID messageId
) {
}

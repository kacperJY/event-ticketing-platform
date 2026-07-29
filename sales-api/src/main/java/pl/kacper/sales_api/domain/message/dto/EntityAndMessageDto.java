package pl.kacper.sales_api.domain.message.dto;


import java.util.UUID;

public record EntityAndMessageDto <E>(
        E entityId,
        UUID messageID
) {
}

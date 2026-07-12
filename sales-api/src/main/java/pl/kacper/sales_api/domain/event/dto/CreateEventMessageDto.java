package pl.kacper.sales_api.domain.event.dto;

import java.math.BigInteger;

public record CreateEventMessageDto(
        Long eventId,
        BigInteger pricePerSeat
) {
}

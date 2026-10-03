package pl.kacper.sales_api.domain.message.dto.message_payload;

public record CreatedEventMessagePayloadDto(
        Long eventId,
        long pricePerSeat,
        int placesNumber,
        String seatPrefix
) {
}

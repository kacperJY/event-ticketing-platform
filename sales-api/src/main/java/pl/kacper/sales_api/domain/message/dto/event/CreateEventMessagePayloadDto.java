package pl.kacper.sales_api.domain.message.dto.event;

public record CreateEventMessagePayloadDto(
        Long eventId,
        long pricePerSeat,
        int placesNumber,
        String seatPrefix
) {
}

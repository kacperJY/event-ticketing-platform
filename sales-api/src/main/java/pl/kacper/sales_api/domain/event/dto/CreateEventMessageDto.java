package pl.kacper.sales_api.domain.event.dto;

public record CreateEventMessageDto(
        Long eventId,
        long pricePerSeat,
        int placesNumber,
        String seatPrefix
) {
}

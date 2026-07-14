package pl.kacper.sales_api.domain.event.dto;

public record SimpleEventDto(
        Long eventId,
        String name,
        String city
) {
}

package pl.kacper.sales_api.domain.event.dto;

import pl.kacper.sales_api.domain.event.EventCategory;

import java.time.Instant;

public record DetailEventDto(
        Long eventId,
        String name,
        String description,
        EventCategory eventCategory,
        Address location,
        Instant eventDate,
        int placesNumber,
        int availablePlaces
) {
}

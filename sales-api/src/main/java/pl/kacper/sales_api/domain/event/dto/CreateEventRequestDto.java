package pl.kacper.sales_api.domain.event.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import pl.kacper.sales_api.domain.event.EventCategory;

import java.time.Instant;

public record CreateEventRequestDto(
        @NotBlank String name,
        @NotBlank String description,
        EventCategory eventCategory,
        Address location,
        long seatPrice,
        @Future Instant eventDate,
        @Positive int placesNumber
) {
}

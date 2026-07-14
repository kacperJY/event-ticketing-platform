package pl.kacper.sales_api.domain.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import pl.kacper.sales_api.domain.event.EventCategory;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;

public record CreateEventRequestDto(
        @NotBlank String name,
        @NotBlank String description,
        EventCategory eventCategory,
        Address location,
        long seatPrice,
        Instant eventDate,
        @Positive int placesNumber
) {
}

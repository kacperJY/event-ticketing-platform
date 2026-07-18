package pl.kacper.sales_api.domain.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.jspecify.annotations.NonNull;

public record TicketRequestDto(
        @NonNull Long eventId,
        @Max(1000) @Positive int quantity
) {
}

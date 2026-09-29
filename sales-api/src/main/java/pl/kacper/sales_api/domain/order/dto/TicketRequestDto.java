package pl.kacper.sales_api.domain.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TicketRequestDto(
        @NotNull Long eventId,
        @Max(1000) @Positive int quantity
) {
}

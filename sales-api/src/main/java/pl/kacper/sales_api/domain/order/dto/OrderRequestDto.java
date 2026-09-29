package pl.kacper.sales_api.domain.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record OrderRequestDto(
       @Valid @NotEmpty List<@NotNull TicketRequestDto> tickets
) {
}

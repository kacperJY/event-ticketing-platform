package pl.kacper.sales_api.domain.order.dto;

import java.util.List;

public record OrderRequestDto(
        List<TicketRequestDto> tickets
) {
}

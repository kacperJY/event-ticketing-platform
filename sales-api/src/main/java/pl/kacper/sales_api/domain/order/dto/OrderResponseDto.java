package pl.kacper.sales_api.domain.order.dto;

import pl.kacper.sales_api.domain.order.OrderStatus;

import java.time.Instant;
import java.util.UUID;

public record OrderResponseDto(
        UUID orderID,
        String purchaser,
        long fullPrice,
        Instant orderCreatedDate,
        OrderStatus orderStatus
) {
}

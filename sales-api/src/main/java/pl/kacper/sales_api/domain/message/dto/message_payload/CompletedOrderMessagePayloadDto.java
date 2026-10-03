package pl.kacper.sales_api.domain.message.dto.message_payload;

import pl.kacper.sales_api.domain.event.EventCategory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CompletedOrderMessagePayloadDto(
        UUID orderId,
        String purchaserEmail,
        Instant paidAt,
        List<Item> itemList
) {
    public record Item(
            Long orderItemId,

            String eventName,
            String country,
            String city,
            String street,
            String no,
            String postalCode,

            Instant eventDate,
            EventCategory eventCategory,

            String seatNumber
    ){

    }
}

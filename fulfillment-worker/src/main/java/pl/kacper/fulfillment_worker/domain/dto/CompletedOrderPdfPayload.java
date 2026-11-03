package pl.kacper.fulfillment_worker.domain.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CompletedOrderPdfPayload(
        UUID orderId,
        Instant paidAt,
        List<Item> itemList
) {

    public record Item(
            UUID ticketId,
            String eventName,
            String country,
            String city,
            String street,
            String no,
            String postalCode,

            Instant eventDate,
            String eventCategory,

            String seatNumber
    ) {

    }

}

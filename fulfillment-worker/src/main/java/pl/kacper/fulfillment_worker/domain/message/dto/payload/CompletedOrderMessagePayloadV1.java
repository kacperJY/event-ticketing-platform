package pl.kacper.fulfillment_worker.domain.message.dto.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CompletedOrderMessagePayloadV1(
        @NotNull UUID orderId,
        @NotBlank @Email String purchaserEmail,
        @NotNull Instant paidAt,
        @NotEmpty List<@Valid @NotNull Item> itemList
) {

    public record Item(
            @NotNull @Positive Long orderItemId,

            @NotBlank String eventName,
            @NotBlank String country,
            @NotBlank String city,
            @NotBlank String street,
            @NotBlank String no,
            @NotBlank String postalCode,

            @NotNull Instant eventDate,
            @NotBlank String eventCategory,

            @NotBlank String seatNumber
    ) {

    }
}

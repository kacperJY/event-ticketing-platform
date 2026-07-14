package pl.kacper.sales_api.domain.event.dto;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;

@Embeddable
public record Address(
        @NotBlank String country,
        @NotBlank String city,
        @NotBlank String street,
        @NotBlank String no,
        @NotBlank String postalCode
) {
}

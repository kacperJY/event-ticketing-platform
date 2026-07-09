package pl.kacper.sales_api.domain.event;

import jakarta.persistence.Embeddable;

@Embeddable
public record Address(
        String country,
        String city,
        String street,
        String no,
        String postalCode
) {
}

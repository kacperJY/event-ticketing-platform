package pl.kacper.fulfillment_worker.domain.message.dto;

public record MessageHeaderValue(
        String header,
        String value
) {
}

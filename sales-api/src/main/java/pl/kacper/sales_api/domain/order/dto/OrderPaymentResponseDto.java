package pl.kacper.sales_api.domain.order.dto;

public record OrderPaymentResponseDto(
        String paymentId,
        String clientSecret,
        String providerPaymentStatus
) {
}

package pl.kacper.sales_api.domain.order;

public enum PaymentStatus {
    NOT_INITIALIZED,
    PENDING,
    CANCELED,
    SUCCEEDED,
    FAILED,
    REFUNDED,
}

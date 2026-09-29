package pl.kacper.sales_api.domain.order;

public enum PaymentStatus {
    NOT_INITIALIZED,
    PENDING,
    CANCELED,
    SUCCEEDED,
    REFUND_REQUIRED,
    REFUND_PENDING,
    REFUND_FAILED,
    REFUNDED,
}

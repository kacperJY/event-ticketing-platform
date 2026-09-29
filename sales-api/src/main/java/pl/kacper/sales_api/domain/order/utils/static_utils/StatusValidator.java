package pl.kacper.sales_api.domain.order.utils.static_utils;

import pl.kacper.sales_api.domain.order.OrderStatus;
import pl.kacper.sales_api.domain.order.PaymentStatus;

public class StatusValidator {

    public static String createMessageForInvalidOrderStatus(OrderStatus orderStatus) {
        return switch (orderStatus) {
            case COMPLETED -> "Cannot initialize payment for this order that is already completed";
            case CANCELED -> "Cannot initialize payment for this order that it has already been canceled";
            case EXPIRED -> "Cannot initialize payment for this order because order is expired";
            default -> null;
        };
    }

    public static boolean validateOrderStatus(OrderStatus orderStatus) {
        return switch (orderStatus) {
            case COMPLETED, CANCELED, EXPIRED -> false;
            case PENDING -> true;
        };
    }

    public static String createMessageForInvalidPaymentStatus(PaymentStatus paymentStatus) {
        return switch (paymentStatus) {
            case SUCCEEDED -> "Cannot initialize payment because payment has already succeeded";
            case REFUNDED -> "Cannot initialize payment because payment has been already refunded";
            case REFUND_REQUIRED -> "Cannot initialize payment because payment is already waiting for refund";
            case CANCELED -> "Cannot initialize payment because payment has already canceled";
            case REFUND_PENDING -> "Cannot initialize payment because payment is already in state of processing refund";
            case REFUND_FAILED -> "Cannot initialize payment because payment has been ended with refund failure";
            default -> null;
        };
    }

    public static boolean validatePaymentStatus(PaymentStatus paymentStatus) {
        return switch (paymentStatus) {
            case REFUND_REQUIRED, SUCCEEDED, REFUNDED, REFUND_PENDING, REFUND_FAILED, CANCELED -> false;
            case PENDING, NOT_INITIALIZED -> true;
        };
    }
}

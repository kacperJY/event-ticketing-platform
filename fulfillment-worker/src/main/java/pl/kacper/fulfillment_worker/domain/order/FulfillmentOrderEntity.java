package pl.kacper.fulfillment_worker.domain.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@NoArgsConstructor
@Getter

@Entity
@Table(name = "fulfillment_orders")
public class FulfillmentOrderEntity {

    @Id
    @Column(name = "source_order_id")
    private UUID sourceOrderId;

    @Column(name = "purchaser_email", nullable = false)
    private String purchaserEmail;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;


    @Setter
    @Column(name = "delivery_status")
    @Enumerated(EnumType.STRING)
    private DeliveryStatus deliveryStatus;

    @Setter
    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Setter
    @Column(name = "attempt_count")
    private int attemptCount;

    @Setter
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Setter
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Setter
    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;


    public FulfillmentOrderEntity(UUID sourceOrderId, String purchaserEmail, Instant paidAt, Instant createdAt) {
        this.sourceOrderId = sourceOrderId;
        this.purchaserEmail = purchaserEmail;
        this.paidAt = paidAt;
        this.createdAt = createdAt;

        this.deliveryStatus = DeliveryStatus.PENDING;
        this.processingStartedAt = null;
        this.attemptCount = 0;
        this.nextAttemptAt = null;
        this.deliveredAt = null;
    }
}

package pl.kacper.sales_api.domain.order.stripe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@NoArgsConstructor
@Getter

@Entity
@Table(name = "stripe_events")
public class StripeEventEntity {

    @Setter
    @Id
    @Column(name = "stripe_event_id")
    private String stripeEventId;

    @Setter
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Setter
    @Column(name = "payment_intent_id", nullable = false)
    private String paymentIntentId;

    @Setter
    @Column(name = "stripe_event_type", nullable = false)
    private String stripeEventType;

    @Setter
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}

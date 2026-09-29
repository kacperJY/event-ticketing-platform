package pl.kacper.sales_api.domain.order.stripe;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface StripeEventRepository extends ListCrudRepository<StripeEventEntity, String> {

    @Modifying
    @Query(
            value =
                    """
                                insert into stripe_events (stripe_event_id,order_id,payment_intent_id,stripe_event_type, processed_at)
                                values (:stripeEventId,:orderID,:paymentIntentId,:stripeWebhookEventType,:processedAt) on conflict(stripe_event_id) do nothing
                            """,
            nativeQuery = true
    )
    int insertOnConflictDoNothing(@Param("stripeEventId") String stripeEventId,
                                  @Param("orderID") UUID orderID,
                                  @Param("paymentIntentId") String paymentIntentId,
                                  @Param("stripeWebhookEventType") String stripeWebhookEventType,
                                  @Param("processedAt") Instant processedAt);
}

package pl.kacper.fulfillment_worker.domain.order;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.fulfillment_worker.domain.message.dto.payload.CompletedOrderMessagePayloadV1;
import pl.kacper.fulfillment_worker.domain.ticket.TicketEntity;

import java.time.Instant;
import java.util.UUID;

@Service
class CompletedOrderMessageTransactionService {

    @Value("${database.batch-size}")
    private int batchSize;

    private final EntityManager entityManager;

    public CompletedOrderMessageTransactionService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processMessage(String consumerName, UUID messageId, CompletedOrderMessagePayloadV1 completedOrderMessagePayloadV1) {
        int insert = entityManager.createNativeQuery("insert into processed_messages(consumer_name,message_id,processed_at) values (:consumer,:messageId,:processed_at) on conflict (consumer_name, message_id) do nothing", Integer.class)
                .setParameter("consumer", consumerName)
                .setParameter("messageId", messageId)
                .setParameter("processed_at", Instant.now())
                .executeUpdate();

        if (insert == 0) return;

        UUID orderId = completedOrderMessagePayloadV1.orderId();
        FulfillmentOrderEntity fulfillmentOrderEntity = new FulfillmentOrderEntity(
                orderId,
                completedOrderMessagePayloadV1.purchaserEmail(),
                completedOrderMessagePayloadV1.paidAt(),
                Instant.now()
        );

        entityManager.persist(fulfillmentOrderEntity);
        entityManager.flush();
        entityManager.clear();

        int counter = 1;
        for (CompletedOrderMessagePayloadV1.Item item : completedOrderMessagePayloadV1.itemList()) {
            FulfillmentOrderEntity reference = entityManager.getReference(FulfillmentOrderEntity.class, orderId);
            TicketEntity ticketEntity = new TicketEntity(
                    reference,
                    item.orderItemId(),
                    Instant.now(),
                    item.eventName(),
                    item.country(),
                    item.city(),
                    item.street(),
                    item.no(),
                    item.postalCode(),
                    item.eventDate(),
                    item.eventCategory(),
                    item.seatNumber()
            );
            entityManager.persist(ticketEntity);

            if (counter == batchSize) {
                entityManager.flush();
                entityManager.clear();
                counter = 1;
                continue;
            }

            counter++;
        }
        if (counter != 1) {
            entityManager.flush();
            entityManager.clear();
        }
    }
}

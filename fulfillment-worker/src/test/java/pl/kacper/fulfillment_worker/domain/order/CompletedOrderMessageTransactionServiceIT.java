package pl.kacper.fulfillment_worker.domain.order;

import jakarta.persistence.EntityManager;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.kacper.fulfillment_worker.BaseIT;
import pl.kacper.fulfillment_worker.domain.message.ProcessedMessageEntity;
import pl.kacper.fulfillment_worker.domain.message.ProcessedMessageId;
import pl.kacper.fulfillment_worker.domain.message.dto.payload.CompletedOrderMessagePayloadV1;
import pl.kacper.fulfillment_worker.domain.ticket.TicketEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

class CompletedOrderMessageTransactionServiceIT extends BaseIT {

    private static final String CONSUMER_NAME = "TEST-SERVICE";

    private final EntityManager entityManager;
    private final CompletedOrderMessageTransactionService completedOrderMessageTransactionService;

    private static final Instant DATE = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    public CompletedOrderMessageTransactionServiceIT(EntityManager entityManager, CompletedOrderMessageTransactionService completedOrderMessageTransactionService) {
        this.entityManager = entityManager;
        this.completedOrderMessageTransactionService = completedOrderMessageTransactionService;
    }

    private static final List<CompletedOrderMessagePayloadV1.Item> VALID_ORDER_ITEM_LIST = List.of(
            new CompletedOrderMessagePayloadV1.Item(
                    88204L,
                    "Summer Music Festival 2026",
                    "United Kingdom",
                    "London",
                    "Wembley Park Drive",
                    "HA9 0WS",
                    "HA9 0WS",
                    DATE,
                    "Music Festivals",
                    "Section B, Row 14, Seat 22"
            ),
            new CompletedOrderMessagePayloadV1.Item(
                    30195L,
                    "Global AI & Tech Expo",
                    "United States",
                    "San Francisco",
                    "Howard Street",
                    "747",
                    "94103",
                    DATE,
                    "Conferences",
                    "Main Hall, Desk 104"
            )
    );

    private static CompletedOrderMessagePayloadV1.Item mapTicketEntityToOrderItem(TicketEntity ticketEntity) {
        return new CompletedOrderMessagePayloadV1.Item(
                ticketEntity.getSourceOrderItemId(),
                ticketEntity.getEventName(),
                ticketEntity.getCountry(),
                ticketEntity.getCity(),
                ticketEntity.getStreet(),
                ticketEntity.getNo(),
                ticketEntity.getPostalCode(),
                ticketEntity.getEventDate(),
                ticketEntity.getEventCategory(),
                ticketEntity.getSeatNumber()
        );
    }

    @Test
    void shouldSuccessfullyProcessMessage() {
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String purchaserEmail = "mail@mail.com";
        ProcessedMessageId processedMessageId = new ProcessedMessageId(CONSUMER_NAME, messageId);

        CompletedOrderMessagePayloadV1 completedOrderMessagePayloadV1 = new CompletedOrderMessagePayloadV1(
                orderId,
                purchaserEmail,
                DATE,
                VALID_ORDER_ITEM_LIST
        );

        completedOrderMessageTransactionService.processMessage(processedMessageId.consumerName(), processedMessageId.messageId(), completedOrderMessagePayloadV1);

        List<ProcessedMessageEntity> processedMessages = entityManager.createQuery("select processedMessage from ProcessedMessageEntity processedMessage where processedMessage.processedMessageId=:processedMessageId", ProcessedMessageEntity.class)
                .setParameter("processedMessageId", processedMessageId)
                .getResultList();
        Assertions.assertThat(processedMessages.size()).isEqualTo(1);
        ProcessedMessageEntity singleProcessedMessage = processedMessages.getFirst();
        Assertions.assertThat(singleProcessedMessage.getProcessedMessageId()).isEqualTo(processedMessageId);
        Assertions.assertThat(singleProcessedMessage.getProcessedAt()).isNotNull();

        List<FulfillmentOrderEntity> orderList = entityManager.createQuery("select fulfillmentOrder from FulfillmentOrderEntity fulfillmentOrder where fulfillmentOrder.sourceOrderId=:orderId", FulfillmentOrderEntity.class)
                .setParameter("orderId", orderId)
                .getResultList();
        Assertions.assertThat(orderList.size()).isEqualTo(1);
        FulfillmentOrderEntity fulfillmentOrder = orderList.getFirst();
        Assertions.assertThat(fulfillmentOrder.getSourceOrderId()).isEqualTo(orderId);
        Assertions.assertThat(fulfillmentOrder.getCreatedAt()).isNotNull();
        Assertions.assertThat(fulfillmentOrder.getPurchaserEmail()).isEqualTo(purchaserEmail);
        Assertions.assertThat(fulfillmentOrder.getPaidAt()).isNotNull();
        Assertions.assertThat(fulfillmentOrder.getAttemptCount()).isEqualTo(0);
        Assertions.assertThat(fulfillmentOrder.getNextAttemptAt()).isNull();
        Assertions.assertThat(fulfillmentOrder.getLastError()).isNull();
        Assertions.assertThat(fulfillmentOrder.getProcessingStartedAt()).isNull();
        Assertions.assertThat(fulfillmentOrder.getDeliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        Assertions.assertThat(fulfillmentOrder.getDeliveredAt()).isNull();

        List<TicketEntity> ticketEntityList = entityManager.createQuery("select ticket from TicketEntity ticket order by ticket.sourceOrderItemId asc", TicketEntity.class)
                .getResultList();
        Assertions.assertThat(ticketEntityList.size()).isEqualTo(VALID_ORDER_ITEM_LIST.size());


        List<CompletedOrderMessagePayloadV1.Item> dtoListFromTicketEntityList = ticketEntityList.stream()
                .map(CompletedOrderMessageTransactionServiceIT::mapTicketEntityToOrderItem)
                .toList();
        Assertions.assertThat(dtoListFromTicketEntityList).containsExactlyInAnyOrderElementsOf(VALID_ORDER_ITEM_LIST);

    }

    @Test
    void shouldIgnoreAlreadyProcessedMessage() {
        UUID orderIdFirst = UUID.randomUUID();
        UUID orderIdSecond = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String purchaserEmailFirst = "first@mail.com";
        String purchaserEmailSecond = "second@mail.com";

        ProcessedMessageId processedMessageId = new ProcessedMessageId(CONSUMER_NAME, messageId);

        CompletedOrderMessagePayloadV1 completedOrderMessagePayloadV1First = new CompletedOrderMessagePayloadV1(
                orderIdFirst,
                purchaserEmailFirst,
                DATE,
                VALID_ORDER_ITEM_LIST
        );

        CompletedOrderMessagePayloadV1 completedOrderMessagePayloadV1Second = new CompletedOrderMessagePayloadV1(
                orderIdSecond,
                purchaserEmailSecond,
                DATE,
                VALID_ORDER_ITEM_LIST
        );

        completedOrderMessageTransactionService.processMessage(CONSUMER_NAME, messageId, completedOrderMessagePayloadV1First);

        completedOrderMessageTransactionService.processMessage(CONSUMER_NAME, messageId, completedOrderMessagePayloadV1Second); // IDEMPOTENT

        List<ProcessedMessageEntity> processedMessageList = entityManager.createQuery("select pm from ProcessedMessageEntity pm", ProcessedMessageEntity.class).getResultList();
        Assertions.assertThat(processedMessageList.size()).isEqualTo(1);

        FulfillmentOrderEntity fulfillmentOrderFirst = entityManager.createQuery("select foe from FulfillmentOrderEntity foe where foe.sourceOrderId=:orderId", FulfillmentOrderEntity.class)
                .setParameter("orderId", orderIdFirst)
                .getSingleResult();
        Assertions.assertThat(fulfillmentOrderFirst).isNotNull();
        Assertions.assertThat(fulfillmentOrderFirst.getPurchaserEmail()).isEqualTo(purchaserEmailFirst);

        List<TicketEntity> ticketListFirst = entityManager.createQuery("select te from TicketEntity te where te.fulfillmentOrderEntity.sourceOrderId=:orderId", TicketEntity.class)
                .setParameter("orderId", orderIdFirst)
                .getResultList();
        Assertions.assertThat(ticketListFirst.size()).isEqualTo(VALID_ORDER_ITEM_LIST.size());


        FulfillmentOrderEntity fulfillmentOrderSecond = entityManager.createQuery("select foe from FulfillmentOrderEntity foe where foe.sourceOrderId=:orderId", FulfillmentOrderEntity.class)
                .setParameter("orderId", orderIdSecond)
                .getSingleResultOrNull();
        Assertions.assertThat(fulfillmentOrderSecond).isNull();

        List<TicketEntity> ticketListSecond = entityManager.createQuery("select te from TicketEntity te where te.fulfillmentOrderEntity.sourceOrderId=:orderId", TicketEntity.class)
                .setParameter("orderId", orderIdSecond)
                .getResultList();
        Assertions.assertThat(ticketListSecond).isEmpty();
    }

    @Test
    void shouldResolveIdempotencyConflict() {
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String purchaserEmail = "first@mail.com";

        ProcessedMessageId processedMessageId = new ProcessedMessageId(CONSUMER_NAME, messageId);

        CompletedOrderMessagePayloadV1 completedOrderMessagePayloadV1 = new CompletedOrderMessagePayloadV1(
                orderId,
                purchaserEmail,
                DATE,
                VALID_ORDER_ITEM_LIST
        );

        CountDownLatch acquire = new CountDownLatch(2);


        Runnable runnable = () -> {
            acquire.countDown();
            try {
                boolean await = acquire.await(5, TimeUnit.SECONDS);
                if (!await)
                    throw new TimeoutException("Timeout of waiting for notify - Thread=" + Thread.currentThread().getName());
                completedOrderMessageTransactionService.processMessage(CONSUMER_NAME, messageId, completedOrderMessagePayloadV1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Assertions.fail("Thread test task has been interrupted - Thread=" + Thread.currentThread().getName(), e);
            } catch (TimeoutException e) {
                Assertions.fail(e.getMessage(), e);
            }
        };
        try (
                ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        ) {

            CompletableFuture<Void> result1 = CompletableFuture.runAsync(runnable, executorService);
            CompletableFuture<Void> result2 = CompletableFuture.runAsync(runnable, executorService);

            result1.join();
            result2.join();

            List<ProcessedMessageEntity> processedMessageEntityList = entityManager.createQuery("select pme from ProcessedMessageEntity pme where pme.processedMessageId=:processedMessageId", ProcessedMessageEntity.class)
                    .setParameter("processedMessageId", processedMessageId)
                    .getResultList();
            Assertions.assertThat(processedMessageEntityList.size()).isEqualTo(1);

            List<FulfillmentOrderEntity> fulfillmentOrderList = entityManager.createQuery("select foe from FulfillmentOrderEntity foe where foe.sourceOrderId=:orderId", FulfillmentOrderEntity.class)
                    .setParameter("orderId", orderId)
                    .getResultList();
            Assertions.assertThat(fulfillmentOrderList.size()).isEqualTo(1);

            List<TicketEntity> ticketEntityList = entityManager.createQuery("select te from TicketEntity te where te.fulfillmentOrderEntity.sourceOrderId=:orderId", TicketEntity.class)
                    .setParameter("orderId", orderId)
                    .getResultList();
            Assertions.assertThat(ticketEntityList.size()).isEqualTo(VALID_ORDER_ITEM_LIST.size());
        }
    }
}

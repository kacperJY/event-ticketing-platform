package pl.kacper.fulfillment_worker.domain.order.completion;

import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.exception.NoSuchDbRecordException;
import pl.kacper.exception.OrderIllegalFlowException;
import pl.kacper.fulfillment_worker.domain.dto.CompletedOrderPdfPayload;
import pl.kacper.fulfillment_worker.domain.order.DeliveryStatus;
import pl.kacper.fulfillment_worker.domain.order.FulfillmentOrderEntity;
import pl.kacper.fulfillment_worker.domain.order.FulfillmentOrderRepository;
import pl.kacper.fulfillment_worker.domain.ticket.TicketEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FulfillmentOrderCompletionTransactionService {

    private final FulfillmentOrderRepository fulfillmentOrderRepository;
    private final EntityManager entityManager;

    public FulfillmentOrderCompletionTransactionService(FulfillmentOrderRepository fulfillmentOrderRepository, EntityManager entityManager) {
        this.fulfillmentOrderRepository = fulfillmentOrderRepository;
        this.entityManager = entityManager;
    }

    // TX1
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    List<FulfillmentOrderCompletionService.ProcessingOrderTask> findAndPrepareOrdersCandidates(int fetchSize, int maxRetries) {
        PageRequest pageRequest = PageRequest.of(0, fetchSize);
        List<FulfillmentOrderEntity> orderCandidatesForProcessing = fulfillmentOrderRepository.findOrderCandidatesForProcessingWithSkipLocked(
                Instant.now(),
                Instant.now().minus(Duration.ofSeconds(90)),
                pageRequest
        );

        // AMORTIZATION when TX2-FAILURE-CASES has been interrupted
        orderCandidatesForProcessing.stream()
                .filter(fulfillmentOrder -> fulfillmentOrder.getAttemptCount() >= maxRetries)
                .forEach(fulfillmentOrder -> {
                    fulfillmentOrder.setDeliveryStatus(DeliveryStatus.FAILED);
                    fulfillmentOrder.setNextAttemptAt(null);
                    fulfillmentOrder.setLastError("""
                            Out of processing retries, next retry would be out of bound: %d/%d.
                            No information about last error details.
                            """
                            .formatted((fulfillmentOrder.getAttemptCount() + 1), maxRetries));
                });


        List<UUID> orderToProcessIds = orderCandidatesForProcessing.stream().map(FulfillmentOrderEntity::getSourceOrderId).toList();
        Map<UUID, List<TicketEntity>> orderIdWithTicketsMap = entityManager.createQuery(
                        """
                                    select ticket from TicketEntity ticket where ticket.fulfillmentOrderEntity.sourceOrderId in(:orderIds)
                                """, TicketEntity.class)
                .setParameter("orderIds", orderToProcessIds)
                .getResultList()
                .stream()
                .collect(Collectors.groupingBy(
                        ticketEntity -> ticketEntity.getFulfillmentOrderEntity().getSourceOrderId(),
                        Collectors.toList()
                ));

        return validateAndMapOrderWithTicketsToProcessingOrderTask(orderCandidatesForProcessing, orderIdWithTicketsMap);

    }

    // TX2 - FAILURE
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void checkAvailableRetriesAndUpdateOrder(UUID orderId, int expectedRetry, int maxRetries, String errorMessage, Instant nextAttemptAt) {
        FulfillmentOrderEntity fulfillmentOrderEntity = fulfillmentOrderRepository.findBySourceOrderIdWithLockWaiting1000(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Cannot find FulfillmentOrderEntity with sourceOrderId=" + orderId));

        int attemptCount = fulfillmentOrderEntity.getAttemptCount();

        // Check if it's correct Order flow
        if (attemptCount != expectedRetry)
            throw new IllegalStateException("""
                    Illegal order state between transactions. Probably order has been updated by other process.
                    OrderId: %s
                    Expected retry number: %s
                    Provided retry number: %s
                    """.formatted(orderId, expectedRetry, attemptCount));

        // Validate Delivery Status
        if (fulfillmentOrderEntity.getDeliveryStatus() == DeliveryStatus.PENDING)
            throw new OrderIllegalFlowException("Cannot handle order processing FAILURE when Order delivery status is equal to PENDING");
        else if (fulfillmentOrderEntity.getDeliveryStatus() == DeliveryStatus.SENT)
            throw new OrderIllegalFlowException("Cannot handle order processing FAILURE when Order has been already SENT successfully");
        else if (fulfillmentOrderEntity.getDeliveryStatus() == DeliveryStatus.FAILED)
            throw new OrderIllegalFlowException("Cannot handle order processing FAILURE when Order has been already marked as FAILED");


        // Out of retries
        if (attemptCount >= maxRetries) {
            fulfillmentOrderEntity.setDeliveryStatus(DeliveryStatus.FAILED);
            fulfillmentOrderEntity.setNextAttemptAt(null);
            fulfillmentOrderEntity.setLastError("""
                    Out of processing retries, next retry would be out of bound: %d/%d
                    Error details: %s
                    """
                    .formatted(fulfillmentOrderEntity.getAttemptCount() + 1, maxRetries, errorMessage));
        } else {
            fulfillmentOrderEntity.setDeliveryStatus(DeliveryStatus.PENDING);
            fulfillmentOrderEntity.setNextAttemptAt(nextAttemptAt);
            fulfillmentOrderEntity.setLastError("Error details: " + errorMessage);
        }
    }

    // TX2 - SUCCESS
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void updateCompletedOrder(UUID orderId, int expectedRetry) {
        FulfillmentOrderEntity fulfillmentOrderEntity = fulfillmentOrderRepository.findBySourceOrderIdWithLockWaiting1000(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Cannot find FulfillmentOrderEntity with sourceOrderId=" + orderId));

        int attemptCount = fulfillmentOrderEntity.getAttemptCount();

        // Check if it's correct Order flow
        if (attemptCount != expectedRetry)
            throw new IllegalStateException("""
                    Illegal order state between transactions. Probably order has been updated by other process.
                    OrderId: %s
                    Expected retry number: %s
                    Provided retry number: %s
                    """.formatted(orderId, expectedRetry, attemptCount));

        // Validate Delivery Status
        if (fulfillmentOrderEntity.getDeliveryStatus() == DeliveryStatus.PENDING)
            throw new OrderIllegalFlowException("Cannot handle order processing SUCCESS when Order delivery status is equal to PENDING");
        else if (fulfillmentOrderEntity.getDeliveryStatus() == DeliveryStatus.FAILED)
            throw new OrderIllegalFlowException("Cannot handle order processing SUCCESS when Order has been already marked as FAILED");
        else if (fulfillmentOrderEntity.getDeliveryStatus() == DeliveryStatus.SENT)
            throw new OrderIllegalFlowException("Cannot handle order processing SUCCESS when Order has been already marked as SENT");

        fulfillmentOrderEntity.setDeliveredAt(Instant.now());
        fulfillmentOrderEntity.setDeliveryStatus(DeliveryStatus.SENT);

    }

    private List<FulfillmentOrderCompletionService.ProcessingOrderTask> validateAndMapOrderWithTicketsToProcessingOrderTask(List<FulfillmentOrderEntity> allOrders, Map<UUID, List<TicketEntity>> orderIdWithTicketsMap) {
        ArrayList<FulfillmentOrderCompletionService.ProcessingOrderTask> processingOrderTasks = new ArrayList<>();

        for (FulfillmentOrderEntity order : allOrders) {

            if (order.getDeliveryStatus() == DeliveryStatus.FAILED) continue;

            if (!orderIdWithTicketsMap.containsKey(order.getSourceOrderId())) {
                order.setDeliveryStatus(DeliveryStatus.FAILED);
                order.setNextAttemptAt(null);
                order.setLastError("""
                        Cannot process Order with no tickets. Order will be marked as FAILED and not processing anymore
                        OrderId: %s
                        """
                        .formatted(order.getSourceOrderId()));
                continue;
            }
            List<TicketEntity> tickets = orderIdWithTicketsMap.get(order.getSourceOrderId());


            // UPDATE ENTITY
            order.setNextAttemptAt(null);
            order.setLastError(null);
            order.setAttemptCount(order.getAttemptCount() + 1);
            order.setDeliveryStatus(DeliveryStatus.PROCESSING);
            order.setProcessingStartedAt(Instant.now());

            List<CompletedOrderPdfPayload.Item> ticketItemList = tickets.stream()
                    .map(ticketEntity -> {
                        return new CompletedOrderPdfPayload.Item(
                                ticketEntity.getTicketId(),
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
                    })
                    .toList();

            processingOrderTasks.add(
                    new FulfillmentOrderCompletionService.ProcessingOrderTask(
                            order.getAttemptCount(),
                            order.getPurchaserEmail(),
                            new CompletedOrderPdfPayload(
                                    order.getSourceOrderId(),
                                    order.getPaidAt(),
                                    ticketItemList
                            )
                    ));
        }
        return processingOrderTasks;
    }

}

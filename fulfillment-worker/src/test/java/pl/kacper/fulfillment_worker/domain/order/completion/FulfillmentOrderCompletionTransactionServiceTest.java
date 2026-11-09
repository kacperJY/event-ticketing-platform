package pl.kacper.fulfillment_worker.domain.order.completion;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import pl.kacper.exception.NoSuchDbRecordException;
import pl.kacper.exception.OrderIllegalFlowException;
import pl.kacper.fulfillment_worker.domain.dto.CompletedOrderPdfPayload;
import pl.kacper.fulfillment_worker.domain.order.DeliveryStatus;
import pl.kacper.fulfillment_worker.domain.order.FulfillmentOrderEntity;
import pl.kacper.fulfillment_worker.domain.order.FulfillmentOrderRepository;
import pl.kacper.fulfillment_worker.domain.ticket.TicketEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FulfillmentOrderCompletionTransactionServiceTest {

    private static final Instant PAID_AT = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-11-01T09:55:00Z");
    private static final Instant EVENT_AT = Instant.parse("2026-12-01T18:00:00Z");
    private static final int MAX_RETRIES = 5;

    @Mock private FulfillmentOrderRepository repository;
    @Mock private EntityManager entityManager;
    @Mock private TypedQuery<TicketEntity> ticketQuery;
    @InjectMocks private FulfillmentOrderCompletionTransactionService service;

    @Test
    void tx1PreparesPendingOrderAndMapsTickets() {
        FulfillmentOrderEntity order = order();
        TicketEntity first = ticket(order, 10L, "A-1");
        TicketEntity second = ticket(order, 11L, "A-2");
        stubCandidates(List.of(order), List.of(first, second));

        List<FulfillmentOrderCompletionService.ProcessingOrderTask> tasks =
                service.findAndPrepareOrdersCandidates(4, MAX_RETRIES);

        assertThat(tasks).hasSize(1);
        var task = tasks.getFirst();
        assertThat(task.attemptCount()).isEqualTo(1);
        assertThat(task.purchaserEmail()).isEqualTo("buyer@example.com");
        CompletedOrderPdfPayload payload = task.completedOrderPdfPayload();
        assertThat(payload.orderId()).isEqualTo(order.getSourceOrderId());
        assertThat(payload.paidAt()).isEqualTo(PAID_AT);
        assertThat(payload.itemList()).hasSize(2);
        assertThat(payload.itemList()).extracting(CompletedOrderPdfPayload.Item::ticketId)
                .containsExactlyInAnyOrder(first.getTicketId(), second.getTicketId());
        assertThat(payload.itemList()).extracting(CompletedOrderPdfPayload.Item::seatNumber)
                .containsExactlyInAnyOrder("A-1", "A-2");
        assertThat(payload.itemList().getFirst().eventName()).isEqualTo("Concert");
        assertThat(payload.itemList().getFirst().country()).isEqualTo("Poland");
        assertThat(payload.itemList().getFirst().city()).isEqualTo("Warsaw");
        assertThat(payload.itemList().getFirst().street()).isEqualTo("Main Street");
        assertThat(payload.itemList().getFirst().no()).isEqualTo("12");
        assertThat(payload.itemList().getFirst().postalCode()).isEqualTo("00-001");
        assertThat(payload.itemList().getFirst().eventDate()).isEqualTo(EVENT_AT);
        assertThat(payload.itemList().getFirst().eventCategory()).isEqualTo("MUSIC");
        assertThat(order.getDeliveryStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(order.getAttemptCount()).isEqualTo(1);
        assertThat(order.getProcessingStartedAt()).isNotNull();
        assertThat(order.getNextAttemptAt()).isNull();
        assertThat(order.getLastError()).isNull();
        verify(repository).findOrderCandidatesForProcessingWithSkipLocked(
                any(Instant.class), any(Instant.class), eq(Pageable.ofSize(4)));
    }

    @Test
    void tx1PreparesDueRetryAndClearsPreviousError() {
        FulfillmentOrderEntity order = order();
        Instant previousAttempt = Instant.parse("2026-11-01T11:00:00Z");
        order.setAttemptCount(2);
        order.setProcessingStartedAt(previousAttempt);
        order.setNextAttemptAt(Instant.parse("2026-11-01T11:01:00Z"));
        order.setLastError("SMTP unavailable");
        stubCandidates(List.of(order), List.of(ticket(order, 10L, "A-1")));

        var tasks = service.findAndPrepareOrdersCandidates(1, MAX_RETRIES);

        assertThat(tasks).singleElement().satisfies(task -> assertThat(task.attemptCount()).isEqualTo(3));
        assertThat(order.getDeliveryStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(order.getAttemptCount()).isEqualTo(3);
        assertThat(order.getProcessingStartedAt()).isAfter(previousAttempt);
        assertThat(order.getNextAttemptAt()).isNull();
        assertThat(order.getLastError()).isNull();
    }

    @Test
    void tx1ReclaimsOrphanedProcessingOrder() {
        FulfillmentOrderEntity order = processingOrder(2);
        stubCandidates(List.of(order), List.of(ticket(order, 10L, "A-1")));

        var tasks = service.findAndPrepareOrdersCandidates(1, MAX_RETRIES);

        assertThat(tasks).singleElement().satisfies(task -> assertThat(task.attemptCount()).isEqualTo(3));
        assertThat(order.getDeliveryStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(order.getAttemptCount()).isEqualTo(3);
        assertThat(order.getProcessingStartedAt()).isAfter(Instant.parse("2026-11-01T11:00:00Z"));
    }

    @Test
    void tx1MarksExhaustedOrphanFailedWithoutCreatingTask() {
        FulfillmentOrderEntity order = processingOrder(MAX_RETRIES);
        stubCandidates(List.of(order), List.of(ticket(order, 10L, "A-1")));

        assertThat(service.findAndPrepareOrdersCandidates(1, MAX_RETRIES)).isEmpty();
        assertThat(order.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(order.getAttemptCount()).isEqualTo(MAX_RETRIES);
        assertThat(order.getNextAttemptAt()).isNull();
        assertThat(order.getLastError()).contains("Out of processing retries");
    }

    @Test
    void tx1MarksOrderWithoutTicketsFailedWithoutConsumingAttempt() {
        FulfillmentOrderEntity order = order();
        stubCandidates(List.of(order), List.of());

        assertThat(service.findAndPrepareOrdersCandidates(1, MAX_RETRIES)).isEmpty();
        assertThat(order.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(order.getAttemptCount()).isZero();
        assertThat(order.getNextAttemptAt()).isNull();
        assertThat(order.getLastError()).contains("no tickets");
    }

    @Test
    void tx1ReturnsNoTasksWhenThereAreNoCandidates() {
        stubCandidates(List.of(), List.of());
        assertThat(service.findAndPrepareOrdersCandidates(4, MAX_RETRIES)).isEmpty();
    }

    @Test
    void tx1KeepsValidOrdersWhenOtherCandidatesCannotBeProcessed() {
        FulfillmentOrderEntity exhausted = processingOrder(MAX_RETRIES);
        FulfillmentOrderEntity withoutTickets = order();
        FulfillmentOrderEntity ready = order();
        stubCandidates(List.of(exhausted, withoutTickets, ready),
                List.of(ticket(exhausted, 10L, "A-1"), ticket(ready, 11L, "B-1")));

        var tasks = service.findAndPrepareOrdersCandidates(3, MAX_RETRIES);

        assertThat(tasks).singleElement().satisfies(task ->
                assertThat(task.completedOrderPdfPayload().orderId()).isEqualTo(ready.getSourceOrderId()));
        assertThat(exhausted.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(withoutTickets.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(ready.getDeliveryStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(ready.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void tx2FailureSchedulesRetryWhenAttemptsRemain() {
        FulfillmentOrderEntity order = processingOrder(2);
        Instant nextAttemptAt = Instant.parse("2026-11-01T12:00:00Z");
        stubLockedOrder(order);

        service.checkAvailableRetriesAndUpdateOrder(
                order.getSourceOrderId(), 2, MAX_RETRIES, "SMTP unavailable", nextAttemptAt);

        assertThat(order.getDeliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(order.getAttemptCount()).isEqualTo(2);
        assertThat(order.getNextAttemptAt()).isEqualTo(nextAttemptAt);
        assertThat(order.getLastError()).contains("SMTP unavailable");
        assertThat(order.getDeliveredAt()).isNull();
    }

    @Test
    void tx2FailureMarksOrderFailedAtRetryLimit() {
        FulfillmentOrderEntity order = processingOrder(MAX_RETRIES);
        stubLockedOrder(order);

        service.checkAvailableRetriesAndUpdateOrder(
                order.getSourceOrderId(), MAX_RETRIES, MAX_RETRIES, "PDF error", Instant.now());

        assertThat(order.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(order.getAttemptCount()).isEqualTo(MAX_RETRIES);
        assertThat(order.getNextAttemptAt()).isNull();
        assertThat(order.getLastError()).contains("PDF error");
        assertThat(order.getDeliveredAt()).isNull();
    }

    @Test
    void tx2FailureRejectsStaleAttemptWithoutMutatingOrder() {
        FulfillmentOrderEntity order = processingOrder(3);
        stubLockedOrder(order);

        assertThatThrownBy(() -> service.checkAvailableRetriesAndUpdateOrder(
                order.getSourceOrderId(), 2, MAX_RETRIES, "old error", Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getDeliveryStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(order.getAttemptCount()).isEqualTo(3);
        assertThat(order.getNextAttemptAt()).isNull();
        assertThat(order.getLastError()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PENDING", "SENT", "FAILED"})
    void tx2FailureRejectsNonProcessingStatus(DeliveryStatus status) {
        FulfillmentOrderEntity order = processingOrder(2);
        order.setDeliveryStatus(status);
        stubLockedOrder(order);

        assertThatThrownBy(() -> service.checkAvailableRetriesAndUpdateOrder(
                order.getSourceOrderId(), 2, MAX_RETRIES, "error", Instant.now()))
                .isInstanceOf(OrderIllegalFlowException.class);

        assertThat(order.getDeliveryStatus()).isEqualTo(status);
        assertThat(order.getNextAttemptAt()).isNull();
        assertThat(order.getLastError()).isNull();
    }

    @Test
    void tx2SuccessMarksOrderSent() {
        FulfillmentOrderEntity order = processingOrder(2);
        stubLockedOrder(order);
        Instant before = Instant.now();

        service.updateCompletedOrder(order.getSourceOrderId(), 2);

        assertThat(order.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(order.getAttemptCount()).isEqualTo(2);
        assertThat(order.getDeliveredAt()).isBetween(before, Instant.now());
        assertThat(order.getNextAttemptAt()).isNull();
        assertThat(order.getLastError()).isNull();
    }

    @Test
    void tx2SuccessRejectsStaleAttemptWithoutMutatingOrder() {
        FulfillmentOrderEntity order = processingOrder(3);
        stubLockedOrder(order);

        assertThatThrownBy(() -> service.updateCompletedOrder(order.getSourceOrderId(), 2))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getDeliveryStatus()).isEqualTo(DeliveryStatus.PROCESSING);
        assertThat(order.getAttemptCount()).isEqualTo(3);
        assertThat(order.getDeliveredAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PENDING", "SENT", "FAILED"})
    void tx2SuccessRejectsNonProcessingStatus(DeliveryStatus status) {
        FulfillmentOrderEntity order = processingOrder(2);
        order.setDeliveryStatus(status);
        stubLockedOrder(order);

        assertThatThrownBy(() -> service.updateCompletedOrder(order.getSourceOrderId(), 2))
                .isInstanceOf(OrderIllegalFlowException.class);
        assertThat(order.getDeliveryStatus()).isEqualTo(status);
        assertThat(order.getDeliveredAt()).isNull();
    }

    @Test
    void tx2FailureRejectsUnknownOrder() {
        UUID orderId = UUID.randomUUID();
        when(repository.findBySourceOrderIdWithLockWaiting1000(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkAvailableRetriesAndUpdateOrder(
                orderId, 1, MAX_RETRIES, "error", Instant.now()))
                .isInstanceOf(NoSuchDbRecordException.class);
    }

    @Test
    void tx2SuccessRejectsUnknownOrder() {
        UUID orderId = UUID.randomUUID();
        when(repository.findBySourceOrderIdWithLockWaiting1000(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCompletedOrder(orderId, 1))
                .isInstanceOf(NoSuchDbRecordException.class);
    }

    private FulfillmentOrderEntity order() {
        return new FulfillmentOrderEntity(UUID.randomUUID(), "buyer@example.com", PAID_AT, CREATED_AT);
    }

    private FulfillmentOrderEntity processingOrder(int attempts) {
        FulfillmentOrderEntity order = order();
        order.setDeliveryStatus(DeliveryStatus.PROCESSING);
        order.setAttemptCount(attempts);
        order.setProcessingStartedAt(Instant.parse("2026-11-01T11:00:00Z"));
        return order;
    }

    private TicketEntity ticket(FulfillmentOrderEntity order, long itemId, String seatNumber) {
        TicketEntity ticket = new TicketEntity(order, itemId, CREATED_AT, "Concert", "Poland", "Warsaw",
                "Main Street", "12", "00-001", EVENT_AT, "MUSIC", seatNumber);
        ReflectionTestUtils.setField(ticket, "ticketId", UUID.randomUUID());
        return ticket;
    }

    private void stubCandidates(List<FulfillmentOrderEntity> candidates, List<TicketEntity> tickets) {
        when(repository.findOrderCandidatesForProcessingWithSkipLocked(
                any(Instant.class), any(Instant.class), any(Pageable.class))).thenReturn(candidates);
        when(entityManager.createQuery(anyString(), eq(TicketEntity.class))).thenReturn(ticketQuery);
        when(ticketQuery.setParameter(eq("orderIds"), any())).thenReturn(ticketQuery);
        when(ticketQuery.getResultList()).thenReturn(tickets);
    }

    private void stubLockedOrder(FulfillmentOrderEntity order) {
        when(repository.findBySourceOrderIdWithLockWaiting1000(order.getSourceOrderId()))
                .thenReturn(Optional.of(order));
    }
}

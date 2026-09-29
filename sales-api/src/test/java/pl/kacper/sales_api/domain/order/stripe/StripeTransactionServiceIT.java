package pl.kacper.sales_api.domain.order.stripe;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.jdbc.Sql;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.common.exception.paymentException.PaymentIntentStateMismatchException;
import pl.kacper.sales_api.domain.BaseIT;
import pl.kacper.sales_api.domain.order.*;
import pl.kacper.sales_api.domain.order.dto.OrderRequestDto;
import pl.kacper.sales_api.domain.order.dto.OrderResponseDto;
import pl.kacper.sales_api.domain.order.dto.TicketRequestDto;
import pl.kacper.sales_api.domain.seat.SeatEntity;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;
import pl.kacper.sales_api.domain.user.UserEntity;
import pl.kacper.sales_api.domain.user.UserRepository;
import pl.kacper.sales_api.utils.TransactionTestUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

public class StripeTransactionServiceIT extends BaseIT {

    private final StripeEventRepository stripeEventRepository;
    private final StripeTransactionService stripeTransactionService;
    private final OrderService orderService;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final TransactionTestUtil transactionTestUtil;
    private final OrderItemRepository orderItemRepository;
    private final SeatRepository seatRepository;
    private final OrderLifecycleService orderLifecycleService;

    @Autowired
    public StripeTransactionServiceIT(StripeEventRepository stripeEventRepository,
                                      StripeTransactionService stripeTransactionService, OrderService orderService, UserRepository userRepository,
                                      OrderRepository orderRepository, TransactionTestUtil transactionTestUtil, OrderItemRepository orderItemRepository, SeatRepository seatRepository,
                                      OrderLifecycleService orderLifecycleService) {
        this.stripeEventRepository = stripeEventRepository;
        this.stripeTransactionService = stripeTransactionService;
        this.orderService = orderService;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.transactionTestUtil = transactionTestUtil;
        this.orderItemRepository = orderItemRepository;
        this.seatRepository = seatRepository;
        this.orderLifecycleService = orderLifecycleService;
    }

    @Test
    @Sql(
            scripts = "classpath:scripts/sql/init_event.sql"
    )
    @DisplayName("SUCCEED event completes the order, marks payment as succeeded and sells reserved seats")
    void shouldProcessSucceedEventAndCompleteOrder() {
        final String paymentIntentId = "pi_123";
        final String stripeEventId = "stripe_event_123";
        final StripeWebhookEvent stripeWebhookEvent = StripeWebhookEvent.PI_SUCCEEDED;

        final int seatReservedNumber = 3;
        final Long eventId = 1L;

        UserEntity userEntity = new UserEntity("mail@test.pl", "TestPass", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        OrderRequestDto orderRequestDto = new OrderRequestDto(
                List.of(new TicketRequestDto(eventId, seatReservedNumber))
        );

        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);

        final UUID orderId = orderResponseDto.orderID();

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));

        // Simulate OrderService and creating paymentIntent
        orderEntity.setStripePaymentIntentId(paymentIntentId);
        orderEntity.setPaymentInitializedAt(Instant.now());
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderRepository.save(orderEntity);

        StripeEventContext stripeEventContext = new StripeEventContext(orderId, paymentIntentId, stripeWebhookEvent);
        stripeTransactionService.processEvent(stripeEventContext, stripeEventId);
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventId)).isTrue();

        StripeEventEntity stripeEventEntity = stripeEventRepository.findById(stripeEventId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no StripeEvent with stripeEventId=%s in Test Database".formatted(stripeEventId)));
        Assertions.assertThat(stripeEventEntity.getStripeEventId()).isEqualTo(stripeEventId);
        Assertions.assertThat(stripeEventEntity.getOrderId()).isEqualTo(orderId);
        Assertions.assertThat(stripeEventEntity.getStripeEventType()).isEqualTo(stripeWebhookEvent.getValue());
        Assertions.assertThat(stripeEventEntity.getPaymentIntentId()).isEqualTo(paymentIntentId);
        Assertions.assertThat(stripeEventEntity.getProcessedAt()).isNotNull();

        OrderEntity orderEntityAfter = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        Assertions.assertThat(orderEntityAfter.getPaidAt()).isNotNull();

        List<Long> seatIdsByOrderIds = orderItemRepository.findSeatIdsByOrderIds(List.of(orderId));
        List<SeatEntity> orderSeats = seatRepository.findAllById(seatIdsByOrderIds);
        Assertions.assertThat(orderSeats).hasSize(seatReservedNumber);
        Assertions.assertThat(orderSeats)
                .allMatch(seat -> seat.getSeatStatus() == SeatStatus.SOLD);

    }

    @Test
    @Sql(
            scripts = "classpath:scripts/sql/init_event.sql"
    )
    @DisplayName("Duplicate Stripe event is ignored after the first successful processing")
    void shouldIgnoreDuplicateEventAfterFirstSuccessfulProcessing() {
        final String paymentIntentId = "pi_123";
        final String stripeEventId = "stripe_event_123";
        final StripeWebhookEvent stripeWebhookEvent = StripeWebhookEvent.PI_SUCCEEDED;

        UserEntity userEntity = new UserEntity("mail@test.pl", "TestPass", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        OrderRequestDto orderRequestDto = new OrderRequestDto(
                List.of(new TicketRequestDto(1L, 3))
        );

        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);

        final UUID orderId = orderResponseDto.orderID();

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));

        // Simulate OrderService and creating paymentIntent
        orderEntity.setStripePaymentIntentId(paymentIntentId);
        orderEntity.setPaymentInitializedAt(Instant.now());
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderRepository.save(orderEntity);

        // FIRST
        stripeTransactionService.processEvent(
                new StripeEventContext(orderId, paymentIntentId, stripeWebhookEvent),
                stripeEventId
        );

        StripeEventEntity stripeEventEntityAfter1 = stripeEventRepository.findById(stripeEventId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no StripeEvent with stripeEventId=%s in Test Database".formatted(stripeEventId)));
        Assertions.assertThat(stripeEventEntityAfter1.getStripeEventId()).isEqualTo(stripeEventId);
        Assertions.assertThat(stripeEventEntityAfter1.getOrderId()).isEqualTo(orderId);
        Assertions.assertThat(stripeEventEntityAfter1.getProcessedAt()).isNotNull();

        OrderEntity orderEntityAfter1 = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfter1.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        Assertions.assertThat(orderEntityAfter1.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        // SECOND
        stripeTransactionService.processEvent(
                new StripeEventContext(orderId, paymentIntentId, stripeWebhookEvent),
                stripeEventId
        );
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventId)).isTrue();

        StripeEventEntity stripeEventEntityAfter2 = stripeEventRepository.findById(stripeEventId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no StripeEvent with stripeEventId=%s in Test Database".formatted(stripeEventId)));
        Assertions.assertThat(stripeEventEntityAfter2.getStripeEventId()).isEqualTo(stripeEventId);
        Assertions.assertThat(stripeEventEntityAfter2.getOrderId()).isEqualTo(orderId);
        Assertions.assertThat(stripeEventEntityAfter2.getProcessedAt()).isEqualTo(stripeEventEntityAfter1.getProcessedAt());

        OrderEntity orderEntityAfter2 = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfter2.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        Assertions.assertThat(orderEntityAfter2.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        List<Long> seatIdsByOrderIds = orderItemRepository.findSeatIdsByOrderIds(List.of(orderId));
        List<SeatEntity> orderSeats = seatRepository.findAllById(seatIdsByOrderIds);
        Assertions.assertThat(orderSeats).hasSize(3);
        Assertions.assertThat(orderSeats)
                .allMatch(seat -> seat.getSeatStatus() == SeatStatus.SOLD);
    }


    @Test
    @Sql(
            scripts = "classpath:scripts/sql/init_event.sql"
    )
    @DisplayName("Acknowledged inconsistent event is committed without changing the order state")
    void shouldCommitEventWithoutStateChangeForAcknowledgedInconsistentState() {
        final String paymentIntentId = "pi_123";
        final String stripeEventId = "stripe_event_123";
        final StripeWebhookEvent stripeWebhookEvent = StripeWebhookEvent.PI_SUCCEEDED;

        UserEntity userEntity = new UserEntity("mail@test.pl", "TestPass", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        OrderRequestDto orderRequestDto = new OrderRequestDto(
                List.of(new TicketRequestDto(1L, 3))
        );

        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);

        final UUID orderId = orderResponseDto.orderID();

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));

        // Simulate OrderService and creating paymentIntent
        orderEntity.setStripePaymentIntentId(paymentIntentId);
        orderEntity.setPaymentInitializedAt(Instant.now());
        orderEntity.setPaymentStatus(PaymentStatus.CANCELED);
        orderEntity.setOrderStatus(OrderStatus.CANCELED);
        orderRepository.save(orderEntity);

        stripeTransactionService.processEvent(
                new StripeEventContext(orderId, paymentIntentId, stripeWebhookEvent),
                stripeEventId
        );
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventId)).isTrue();

        StripeEventEntity stripeEventEntity = stripeEventRepository.findById(stripeEventId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no StripeEvent with stripeEventId=%s in Test Database".formatted(stripeEventId)));
        Assertions.assertThat(stripeEventEntity.getStripeEventId()).isEqualTo(stripeEventId);
        Assertions.assertThat(stripeEventEntity.getOrderId()).isEqualTo(orderId);
        Assertions.assertThat(stripeEventEntity.getStripeEventType()).isEqualTo(stripeWebhookEvent.getValue());
        Assertions.assertThat(stripeEventEntity.getPaymentIntentId()).isEqualTo(paymentIntentId);
        Assertions.assertThat(stripeEventEntity.getProcessedAt()).isNotNull();

        OrderEntity orderEntityAfter = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
    }


    @Test
    @Sql(
            scripts = "classpath:scripts/sql/init_event.sql"
    )
    @DisplayName("PaymentIntent ID mismatch rolls back the Stripe event claim")
    void shouldRollbackEventClaimWhenPaymentIntentIdDoesNotMatch() {
        final String paymentIntentId = "pi_123";
        final String incomingDifferentPaymentIntentId = "pi_999";
        final String stripeEventId = "stripe_event_123";
        final StripeWebhookEvent stripeWebhookEvent = StripeWebhookEvent.PI_SUCCEEDED;

        UserEntity userEntity = new UserEntity("mail@test.pl", "TestPass", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        OrderRequestDto orderRequestDto = new OrderRequestDto(
                List.of(new TicketRequestDto(1L, 3))
        );

        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);

        final UUID orderId = orderResponseDto.orderID();

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));

        // Simulate OrderService and creating paymentIntent
        orderEntity.setStripePaymentIntentId(paymentIntentId);
        orderEntity.setPaymentInitializedAt(Instant.now());
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderRepository.save(orderEntity);

        // Exception and Rollback - mismatch paymentId
        Assertions.assertThatThrownBy(
                        () -> stripeTransactionService.processEvent(
                                new StripeEventContext(orderId, incomingDifferentPaymentIntentId, stripeWebhookEvent),
                                stripeEventId
                        )
                )
                .isInstanceOf(PaymentIntentStateMismatchException.class);
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventId)).isFalse();

        OrderEntity orderEntityAfter = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @Sql(
            scripts = "classpath:scripts/sql/init_event.sql"
    )
    @DisplayName("Missing order rolls back the Stripe event claim")
    void shouldRollbackEventClaimWhenOrderDoesNotExist() {
        final String paymentIntentId = "pi_123";
        final String stripeEventId = "stripe_event_123";
        final StripeWebhookEvent stripeWebhookEvent = StripeWebhookEvent.PI_SUCCEEDED;

        UUID orderId = UUID.randomUUID();

        // No Order in Database
        Assertions.assertThatThrownBy(
                        () -> stripeTransactionService.processEvent(
                                new StripeEventContext(orderId, paymentIntentId, stripeWebhookEvent),
                                stripeEventId
                        )
                )
                .isInstanceOf(NoSuchDbRecordException.class);
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventId)).isFalse();

        boolean isOrderExists = orderRepository.existsById(orderId);
        Assertions.assertThat(isOrderExists).isFalse();
    }

    @Test
    @Sql(
            scripts = "classpath:scripts/sql/init_event.sql"
    )
    @DisplayName("FAILED event is persisted without changing order or payment state")
    void shouldPersistFailedEventWithoutChangingOrderState() {
        final String paymentIntentId = "pi_123";
        final String stripeEventId = "stripe_event_123";
        final StripeWebhookEvent stripeWebhookEvent = StripeWebhookEvent.PI_FAILED;

        UserEntity userEntity = new UserEntity("mail@test.pl", "TestPass", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        OrderRequestDto orderRequestDto = new OrderRequestDto(
                List.of(new TicketRequestDto(1L, 3))
        );

        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);

        final UUID orderId = orderResponseDto.orderID();

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));

        // Simulate OrderService and creating paymentIntent
        orderEntity.setStripePaymentIntentId(paymentIntentId);
        orderEntity.setPaymentInitializedAt(Instant.now());
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderRepository.save(orderEntity);

        stripeTransactionService.processEvent(
                new StripeEventContext(orderId, paymentIntentId, stripeWebhookEvent),
                stripeEventId
        );
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventId)).isTrue();

        StripeEventEntity stripeEventEntity = stripeEventRepository.findById(stripeEventId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no StripeEvent with stripeEventId=%s in Test Database".formatted(stripeEventId)));
        Assertions.assertThat(stripeEventEntity.getStripeEventId()).isEqualTo(stripeEventId);
        Assertions.assertThat(stripeEventEntity.getOrderId()).isEqualTo(orderId);
        Assertions.assertThat(stripeEventEntity.getStripeEventType()).isEqualTo(stripeWebhookEvent.getValue());
        Assertions.assertThat(stripeEventEntity.getPaymentIntentId()).isEqualTo(paymentIntentId);
        Assertions.assertThat(stripeEventEntity.getProcessedAt()).isNotNull();

        OrderEntity orderEntityAfter = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }


    @Test
    @Sql(
            scripts = "classpath:scripts/sql/init_event.sql"
    )
    @DisplayName("Webhook before local payment initialization rolls back and succeeds on retry after TX2")
    void shouldRollbackBeforePaymentInitializationAndProcessSameEventOnRetry() {
        final String paymentIntentId = "pi_999";
        final String stripeEventId = "stripe_event_123";
        final StripeWebhookEvent stripeWebhookEvent = StripeWebhookEvent.PI_SUCCEEDED;

        UserEntity userEntity = new UserEntity("mail@test.pl", "TestPass", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        OrderRequestDto orderRequestDto = new OrderRequestDto(
                List.of(new TicketRequestDto(1L, 3))
        );

        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);

        final UUID orderId = orderResponseDto.orderID();

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));

        // Simulate OrderService and creating paymentIntent
        orderEntity.setStripePaymentIntentId(null);
        orderEntity.setPaymentInitializedAt(null);
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderRepository.save(orderEntity);

        // Exception and Rollback - paymentId is null
        Assertions.assertThatThrownBy(
                        () -> stripeTransactionService.processEvent(
                                new StripeEventContext(orderId, paymentIntentId, stripeWebhookEvent),
                                stripeEventId
                        )
                )
                .isInstanceOf(PaymentIntentStateMismatchException.class);
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventId)).isFalse();

        OrderEntity orderEntityAfterFailure = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfterFailure.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfterFailure.getPaymentStatus()).isEqualTo(PaymentStatus.NOT_INITIALIZED);

        // SET paymentIntentId in Order - simulate successful TX2
        orderEntityAfterFailure.setStripePaymentIntentId(paymentIntentId);
        orderEntityAfterFailure.setPaymentInitializedAt(Instant.now());
        orderEntityAfterFailure.setPaymentStatus(PaymentStatus.PENDING);
        orderRepository.save(orderEntityAfterFailure);

        // RETRY after set paymentIntentId - SUCCESS
        stripeTransactionService.processEvent(
                new StripeEventContext(orderId, paymentIntentId, stripeWebhookEvent),
                stripeEventId
        );
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventId)).isTrue();

        StripeEventEntity stripeEventEntity = stripeEventRepository.findById(stripeEventId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no StripeEvent with stripeEventId=%s in Test Database".formatted(stripeEventId)));
        Assertions.assertThat(stripeEventEntity.getStripeEventId()).isEqualTo(stripeEventId);
        Assertions.assertThat(stripeEventEntity.getOrderId()).isEqualTo(orderId);
        Assertions.assertThat(stripeEventEntity.getStripeEventType()).isEqualTo(stripeWebhookEvent.getValue());
        Assertions.assertThat(stripeEventEntity.getPaymentIntentId()).isEqualTo(paymentIntentId);
        Assertions.assertThat(stripeEventEntity.getProcessedAt()).isNotNull();

        OrderEntity orderEntityAfterSuccess = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfterSuccess.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        Assertions.assertThat(orderEntityAfterSuccess.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        List<Long> seatIdsByOrderIds = orderItemRepository.findSeatIdsByOrderIds(List.of(orderId));
        List<SeatEntity> orderSeats = seatRepository.findAllById(seatIdsByOrderIds);
        Assertions.assertThat(orderSeats).hasSize(3);
        Assertions.assertThat(orderSeats)
                .allMatch(seat -> seat.getSeatStatus() == SeatStatus.SOLD);
    }

    @Test
    @Sql(
            scripts = "classpath:scripts/sql/init_event.sql"
    )
    @DisplayName("Two concurrent deliveries of the same Stripe event are processed only once")
    void shouldProcessConcurrentDuplicateEventOnlyOnce() {
        final String paymentIntentId = "pi_123";
        final String stripeEventId = "stripe_event_123";
        final StripeWebhookEvent stripeWebhookEvent = StripeWebhookEvent.PI_SUCCEEDED;

        UserEntity userEntity = new UserEntity("mail@test.pl", "TestPass", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        OrderRequestDto orderRequestDto = new OrderRequestDto(
                List.of(new TicketRequestDto(1L, 3))
        );

        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);

        final UUID orderId = orderResponseDto.orderID();

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));

        // Simulate OrderService and creating paymentIntent
        orderEntity.setStripePaymentIntentId(paymentIntentId);
        orderEntity.setPaymentInitializedAt(Instant.now());
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderRepository.save(orderEntity);

        //
        CountDownLatch countDownLatch = new CountDownLatch(2);
        try (
                ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()
        ) {
            CompletableFuture<Void> result1 = CompletableFuture.runAsync(() -> {
                countDownLatch.countDown();
                try {
                    boolean await = countDownLatch.await(5, TimeUnit.SECONDS);
                    if (!await) throw new TimeoutException("The waiting time for resumption has expired");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assertions.fail("Test StripeTransactionServiceIT Thread has been interrupted");
                } catch (TimeoutException e) {
                    Assertions.fail(e.getMessage());
                }
                stripeTransactionService.processEvent(
                        new StripeEventContext(orderId, paymentIntentId, stripeWebhookEvent),
                        stripeEventId
                );
            }, executorService);

            CompletableFuture<Void> result2 = CompletableFuture.runAsync(() -> {
                countDownLatch.countDown();
                try {
                    boolean await = countDownLatch.await(5, TimeUnit.SECONDS);
                    if (!await) throw new TimeoutException("The waiting time for resumption has expired");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assertions.fail("Test StripeTransactionServiceIT Thread has been interrupted");
                } catch (TimeoutException e) {
                    Assertions.fail(e.getMessage());
                }
                stripeTransactionService.processEvent(
                        new StripeEventContext(orderId, paymentIntentId, stripeWebhookEvent),
                        stripeEventId
                );
            }, executorService);

            result1.join();
            result2.join();
        }
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventId)).isTrue();

        StripeEventEntity stripeEventEntity = stripeEventRepository.findById(stripeEventId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no StripeEvent with stripeEventId=%s in Test Database".formatted(stripeEventId)));
        Assertions.assertThat(stripeEventEntity.getStripeEventId()).isEqualTo(stripeEventId);
        Assertions.assertThat(stripeEventEntity.getOrderId()).isEqualTo(orderId);
        Assertions.assertThat(stripeEventEntity.getStripeEventType()).isEqualTo(stripeWebhookEvent.getValue());
        Assertions.assertThat(stripeEventEntity.getPaymentIntentId()).isEqualTo(paymentIntentId);
        Assertions.assertThat(stripeEventEntity.getProcessedAt()).isNotNull();

        OrderEntity orderEntityAfter = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        List<Long> seatIdsByOrderIds = orderItemRepository.findSeatIdsByOrderIds(List.of(orderId));
        List<SeatEntity> orderSeats = seatRepository.findAllById(seatIdsByOrderIds);
        Assertions.assertThat(orderSeats).hasSize(3);
        Assertions.assertThat(orderSeats)
                .allMatch(seat -> seat.getSeatStatus() == SeatStatus.SOLD);
    }

    @Test
    @Sql(
            scripts = "classpath:scripts/sql/init_event.sql"
    )
    @DisplayName("Event is processed when a concurrent claim of the same event rolls back")
    void shouldProcessEventAfterConcurrentClaimOfSameEventRollsBack() {
        final String paymentIntentId = "pi_123";
        final String stripeEventId = "stripe_event_123";
        final StripeWebhookEvent stripeWebhookEvent = StripeWebhookEvent.PI_SUCCEEDED;

        UserEntity userEntity = new UserEntity("mail@test.pl", "TestPass", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        OrderRequestDto orderRequestDto = new OrderRequestDto(
                List.of(new TicketRequestDto(1L, 3))
        );

        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);

        final UUID orderId = orderResponseDto.orderID();

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));

        // Simulate OrderService and creating paymentIntent
        orderEntity.setStripePaymentIntentId(paymentIntentId);
        orderEntity.setPaymentInitializedAt(Instant.now());
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderRepository.save(orderEntity);

        //
        CountDownLatch acquire = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (
                ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()
        ) {
            CompletableFuture<Void> result1 = CompletableFuture.runAsync(() -> {
                // Keep the first transaction open long enough for the second transaction to hit
                // the same stripeEventId and wait on PostgreSQL's uncommitted unique-key conflict.
                // This delay is only a pragmatic test workaround; it is not a synchronization pattern.
                transactionTestUtil.beginAndTimeHoldTransactionRollbackAfterTime(
                        () -> stripeEventRepository.insertOnConflictDoNothing(stripeEventId, UUID.randomUUID(), paymentIntentId, stripeWebhookEvent.getValue(), Instant.now()),
                        acquire,
                        release,
                        5,
                        TimeUnit.SECONDS
                );
            }, executorService);

            CompletableFuture<Void> result2 = CompletableFuture.runAsync(() -> {
                acquire.countDown();
                try {
                    boolean await = acquire.await(5, TimeUnit.SECONDS);
                    if (!await) throw new TimeoutException("The waiting time for resumption has expired");
                    stripeTransactionService.processEvent(
                            new StripeEventContext(orderId, paymentIntentId, stripeWebhookEvent),
                            stripeEventId
                    );
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assertions.fail("Test StripeTransactionServiceIT Thread has been interrupted");
                } catch (TimeoutException e) {
                    Assertions.fail(e.getMessage());
                } finally {
                    release.countDown();
                }
            }, executorService);

            result1.join();
            result2.join();
        }
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventId)).isTrue();

        StripeEventEntity stripeEventEntity = stripeEventRepository.findById(stripeEventId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no StripeEvent with stripeEventId=%s in Test Database".formatted(stripeEventId)));
        Assertions.assertThat(stripeEventEntity.getStripeEventId()).isEqualTo(stripeEventId);
        Assertions.assertThat(stripeEventEntity.getOrderId()).isEqualTo(orderId);
        Assertions.assertThat(stripeEventEntity.getStripeEventType()).isEqualTo(stripeWebhookEvent.getValue());
        Assertions.assertThat(stripeEventEntity.getPaymentIntentId()).isEqualTo(paymentIntentId);
        Assertions.assertThat(stripeEventEntity.getProcessedAt()).isNotNull();

        OrderEntity orderEntityAfter = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        List<Long> seatIdsByOrderIds = orderItemRepository.findSeatIdsByOrderIds(List.of(orderId));
        List<SeatEntity> orderSeats = seatRepository.findAllById(seatIdsByOrderIds);
        Assertions.assertThat(orderSeats).hasSize(3);
        Assertions.assertThat(orderSeats)
                .allMatch(seat -> seat.getSeatStatus() == SeatStatus.SOLD);
    }


    @Test
    @Sql(
            scripts = "classpath:scripts/sql/init_event.sql"
    )
    @DisplayName("Order lock conflict rolls back the Stripe event claim")
    void shouldRollbackEventClaimWhenOrderLockCannotBeAcquired() {
        final String paymentIntentId = "pi_123";

        final String stripeEventIdCancel = "stripe_event_333_cancel";
        final StripeWebhookEvent stripeWebhookEventCancel = StripeWebhookEvent.PI_CANCELED;

        UserEntity userEntity = new UserEntity("mail@test.pl", "TestPass", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        OrderRequestDto orderRequestDto = new OrderRequestDto(
                List.of(new TicketRequestDto(1L, 3))
        );

        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);

        final UUID orderId = orderResponseDto.orderID();

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));

        // Simulate OrderService and creating paymentIntent
        orderEntity.setStripePaymentIntentId(paymentIntentId);
        orderEntity.setPaymentInitializedAt(Instant.now());
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderRepository.save(orderEntity);

        //
        CountDownLatch acquire = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (
                ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()
        ) {
            CompletableFuture<Void> firstResult = CompletableFuture.runAsync(() -> {
                transactionTestUtil.beginAndHoldTransaction(
                        () -> orderRepository.findByOrderIdWithLockingNoWait(orderId),
                        acquire,
                        release
                );
            }, executorService);

            CompletableFuture<Void> secondResult = CompletableFuture.runAsync(() -> {
                acquire.countDown();
                try {
                    boolean await = acquire.await(5, TimeUnit.SECONDS);
                    if (!await) throw new TimeoutException("The waiting time for resumption has expired");
                    stripeTransactionService.processEvent(
                            new StripeEventContext(orderId, paymentIntentId, stripeWebhookEventCancel),
                            stripeEventIdCancel
                    );
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assertions.fail("Test StripeTransactionServiceIT Thread has been interrupted");
                } catch (TimeoutException e) {
                    Assertions.fail(e.getMessage());
                } finally {
                    release.countDown();
                }
            }, executorService);

            Throwable exception = null;
            try {
                firstResult.join();
            } catch (CompletionException e) {
                Assertions.fail("Application infrastructure failure");
            }
            try {
                secondResult.join();
            } catch (CompletionException e) {
                exception = e.getCause();
            }
            Assertions.assertThat(exception).isNotNull();
            Assertions.assertThat(exception).isInstanceOf(CannotAcquireLockException.class);
        }
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventIdCancel)).isFalse();

        OrderEntity orderEntityAfter = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @Sql(
            scripts = "classpath:scripts/sql/init_event.sql"
    )
    @DisplayName("Event claim rolls back on order lock conflict and the same event succeeds on retry")
    void shouldRollbackOnOrderLockConflictAndProcessSameEventOnRetry() {
        final String paymentIntentId = "pi_123";

        final String stripeEventIdCancel = "stripe_event_333_cancel";
        final StripeWebhookEvent stripeWebhookEventCancel = StripeWebhookEvent.PI_CANCELED;

        UserEntity userEntity = new UserEntity("mail@test.pl", "TestPass", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        OrderRequestDto orderRequestDto = new OrderRequestDto(
                List.of(new TicketRequestDto(1L, 3))
        );

        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);

        final UUID orderId = orderResponseDto.orderID();

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));

        // Simulate OrderService and creating paymentIntent
        orderEntity.setStripePaymentIntentId(paymentIntentId);
        orderEntity.setPaymentInitializedAt(Instant.now());
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderRepository.save(orderEntity);

        //
        CountDownLatch acquire = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (
                ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()
        ) {
            CompletableFuture<Void> firstResult = CompletableFuture.runAsync(() -> {
                transactionTestUtil.beginAndHoldTransaction(
                        () -> orderRepository.findByOrderIdWithLockingNoWait(orderId),
                        acquire,
                        release
                );
            }, executorService);

            CompletableFuture<Void> secondResult = CompletableFuture.runAsync(() -> {
                acquire.countDown();
                try {
                    boolean await = acquire.await(5, TimeUnit.SECONDS);
                    if (!await) throw new TimeoutException("The waiting time for resumption has expired");
                    stripeTransactionService.processEvent(
                            new StripeEventContext(orderId, paymentIntentId, stripeWebhookEventCancel),
                            stripeEventIdCancel
                    );
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assertions.fail("Test StripeTransactionServiceIT Thread has been interrupted");
                } catch (TimeoutException e) {
                    Assertions.fail(e.getMessage());
                } finally {
                    release.countDown();
                }
            }, executorService);

            Throwable exception = null;
            try {
                firstResult.join();
            } catch (CompletionException e) {
                Assertions.fail("Application infrastructure failure");
            }
            try {
                secondResult.join();
            } catch (CompletionException e) {
                exception = e.getCause();
            }
            Assertions.assertThat(exception).isNotNull();
            Assertions.assertThat(exception).isInstanceOf(CannotAcquireLockException.class);
        }
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventIdCancel)).isFalse();

        OrderEntity orderEntityAfterFail = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfterFail.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfterFail.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);


        stripeTransactionService.processEvent(
                new StripeEventContext(orderId, paymentIntentId, stripeWebhookEventCancel),
                stripeEventIdCancel
        );
        Assertions.assertThat(stripeEventRepository.existsById(stripeEventIdCancel)).isTrue();

        OrderEntity orderEntityAfterRetry = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        Assertions.assertThat(orderEntityAfterRetry.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
        Assertions.assertThat(orderEntityAfterRetry.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);

        List<Long> seatIdsByOrderIds = orderItemRepository.findSeatIdsByOrderIds(List.of(orderId));
        List<SeatEntity> orderSeats = seatRepository.findAllById(seatIdsByOrderIds);
        Assertions.assertThat(orderSeats).hasSize(3);
        Assertions.assertThat(orderSeats)
                .allMatch(seat -> seat.getSeatStatus() == SeatStatus.AVAILABLE);
    }

    @Test
    @Sql(
            scripts = "classpath:scripts/sql/init_event.sql"
    )
    @DisplayName("Late SUCCEED event marks expired order payment as refund required after retry")
    void shouldMarkExpiredOrderPaymentForRefundWhenSucceedEventIsRetried() {
        final String paymentIntentId = "pi_123";

        final String stripeEventId = "stripe_event_333_succeed";
        final StripeWebhookEvent stripeWebhookEventSucceed = StripeWebhookEvent.PI_SUCCEEDED;

        final int seatReservedNumber = 3;
        final Long eventId = 1L;

        UserEntity userEntity = new UserEntity("mail@test.pl", "TestPass", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        OrderRequestDto orderRequestDto = new OrderRequestDto(
                List.of(new TicketRequestDto(eventId, seatReservedNumber))
        );

        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);

        final UUID orderId = orderResponseDto.orderID();

        List<Long> seatIdsByOrderIds = orderItemRepository.findSeatIdsByOrderIds(List.of(orderId));
        List<SeatEntity> orderSeats = seatRepository.findAllById(seatIdsByOrderIds);
        Assertions.assertThat(orderSeats).hasSize(seatReservedNumber);
        Assertions.assertThat(orderSeats)
                .allMatch(seat -> seat.getSeatStatus() == SeatStatus.LOCKED_FOR_CHECKOUT);

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));

        // Simulate OrderService and creating paymentIntent
        orderEntity.setStripePaymentIntentId(paymentIntentId);
        orderEntity.setPaymentInitializedAt(Instant.now());
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setExpiresAt(Instant.now().minus(Duration.ofMinutes(60)));
        orderRepository.save(orderEntity);

        CountDownLatch acquire = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (
                ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()
        ) {
            CompletableFuture<Void> firstResult = CompletableFuture.runAsync(() -> {
                transactionTestUtil.beginAndHoldTransaction(
                        () -> {
                            orderLifecycleService.checkIfOrderExpiredAndClean(orderId); // new TX
                            orderRepository.findByOrderIdWithLockingNoWait(orderId); // Lock-Helper for main transaction
                        },
                        acquire,
                        release
                );
            }, executorService);

            CompletableFuture<Void> secondResult = CompletableFuture.runAsync(() -> {
                acquire.countDown();
                try {
                    boolean await = acquire.await(5, TimeUnit.SECONDS);
                    if (!await) throw new TimeoutException("The waiting time for resumption has expired");
                    stripeTransactionService.processEvent(
                            new StripeEventContext(orderId, paymentIntentId, stripeWebhookEventSucceed),
                            stripeEventId
                    );
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assertions.fail("Test StripeTransactionServiceIT Thread has been interrupted");
                } catch (TimeoutException e) {
                    Assertions.fail(e.getMessage());
                } finally {
                    release.countDown();
                }
            }, executorService);

            Throwable exception = null;
            try {
                firstResult.join();
            } catch (CompletionException e) {
                Assertions.fail("Application infrastructure failure");
            }
            try {
                secondResult.join();
            } catch (CompletionException e) {
                exception = e.getCause();
            }
            Assertions.assertThat(exception).isNotNull();
            Assertions.assertThat(exception).isInstanceOf(CannotAcquireLockException.class);
            Assertions.assertThat(stripeEventRepository.existsById(stripeEventId)).isFalse();

            OrderEntity orderEntityAfterFail = orderRepository.findById(orderId)
                    .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
            Assertions.assertThat(orderEntityAfterFail.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
            Assertions.assertThat(orderEntityAfterFail.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
            Assertions.assertThat(orderEntityAfterFail.getPaidAt()).isNull();

            List<Long> seatIdsByOrderIdsAfterFail = orderItemRepository.findSeatIdsByOrderIds(List.of(orderId));
            List<SeatEntity> orderSeatsAfterFail = seatRepository.findAllById(seatIdsByOrderIdsAfterFail);
            Assertions.assertThat(orderSeatsAfterFail).hasSize(seatReservedNumber);
            Assertions.assertThat(orderSeatsAfterFail)
                    .allMatch(seat -> seat.getSeatStatus() == SeatStatus.AVAILABLE);


            stripeTransactionService.processEvent(
                    new StripeEventContext(orderId, paymentIntentId, stripeWebhookEventSucceed),
                    stripeEventId
            );
            Assertions.assertThat(stripeEventRepository.existsById(stripeEventId)).isTrue();

            StripeEventEntity stripeEventEntity = stripeEventRepository.findById(stripeEventId)
                    .orElseThrow(() -> new NoSuchDbRecordException("There is no StripeEvent with stripeEventId=%s in Test Database".formatted(stripeEventId)));
            Assertions.assertThat(stripeEventEntity.getStripeEventId()).isEqualTo(stripeEventId);
            Assertions.assertThat(stripeEventEntity.getOrderId()).isEqualTo(orderId);
            Assertions.assertThat(stripeEventEntity.getStripeEventType()).isEqualTo(stripeWebhookEventSucceed.getValue());
            Assertions.assertThat(stripeEventEntity.getPaymentIntentId()).isEqualTo(paymentIntentId);
            Assertions.assertThat(stripeEventEntity.getProcessedAt()).isNotNull();

            OrderEntity orderEntityAfter = orderRepository.findById(orderId)
                    .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
            Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
            Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_REQUIRED);
            Assertions.assertThat(orderEntityAfter.getPaidAt()).isNotNull();

            List<Long> seatIdsByOrderIdsAfter = orderItemRepository.findSeatIdsByOrderIds(List.of(orderId));
            List<SeatEntity> orderSeatsAfter = seatRepository.findAllById(seatIdsByOrderIdsAfter);
            Assertions.assertThat(orderSeatsAfter).hasSize(seatReservedNumber);
            Assertions.assertThat(orderSeatsAfter)
                    .allMatch(seat -> seat.getSeatStatus() == SeatStatus.AVAILABLE);
        }
    }

}

package pl.kacper.sales_api.domain.order;


import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.jdbc.Sql;
import pl.kacper.sales_api.common.exception.ConcurrencyClaimException;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.common.exception.NoSuchQuantityException;
import pl.kacper.sales_api.common.exception.paymentException.InitializationPaymentException;
import pl.kacper.sales_api.domain.BaseIT;
import pl.kacper.sales_api.domain.order.dto.OrderRequestDto;
import pl.kacper.sales_api.domain.order.dto.OrderResponseDto;
import pl.kacper.sales_api.domain.order.dto.TicketRequestDto;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;
import pl.kacper.sales_api.domain.user.UserEntity;
import pl.kacper.sales_api.domain.user.UserRepository;
import pl.kacper.sales_api.utils.TransactionTestUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

public class OrderServiceIT extends BaseIT {

    private final OrderService orderService;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final SeatRepository seatRepository;
    private final OrderTransactionService orderTransactionService;
    private final TransactionTestUtil transactionTestUtil;
    private final OrderExpirationCleaner orderExpirationCleaner;

    @Autowired
    public OrderServiceIT(OrderService orderService, UserRepository userRepository, OrderRepository orderRepository, SeatRepository seatRepository,
                          OrderTransactionService orderTransactionService, TransactionTestUtil transactionTestUtil, OrderExpirationCleaner orderExpirationCleaner) {
        super();
        this.orderService = orderService;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.seatRepository = seatRepository;
        this.orderTransactionService = orderTransactionService;
        this.transactionTestUtil = transactionTestUtil;
        this.orderExpirationCleaner = orderExpirationCleaner;
    }

    @Test
    @DisplayName("Should create only one Order when concurrent requests exceed available Seats")
    @Sql(scripts = {
            "classpath:scripts/sql/init_event.sql"
    })
    void shouldCreateOnlyOneOrderWhenConcurrentRequestsExceedAvailableSeats() {

        UserEntity userEntity1 = new UserEntity("test1@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        UserEntity userEntity2 = new UserEntity("test2@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        userRepository.save(userEntity1);
        userRepository.save(userEntity2);

        CountDownLatch startCountDownLatch = new CountDownLatch(2);

        OrderRequestDto orderRequestDto = new OrderRequestDto(List.of(new TicketRequestDto(1L, 2)));


        try (var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> result1 = createOrderAsync(startCountDownLatch, orderRequestDto, userEntity1, executorService);
            CompletableFuture<Void> result2 = createOrderAsync(startCountDownLatch, orderRequestDto, userEntity2, executorService);

            int failedOperations = 0;

            if (!checkIfSuccessfullyCreatedOrder(result1)) failedOperations++;
            if (!checkIfSuccessfullyCreatedOrder(result2)) failedOperations++;

            Assertions.assertThat(failedOperations).isEqualTo(1);

            long orderCreatedCounter = orderRepository.count();
            int reservedSeats = seatRepository.countByEvent_EventIdAndSeatStatus(1L, SeatStatus.LOCKED_FOR_CHECKOUT);
            int availableSeats = seatRepository.countByEvent_EventIdAndSeatStatus(1L, SeatStatus.AVAILABLE);

            Assertions.assertThat(orderCreatedCounter).isEqualTo(1);
            Assertions.assertThat(reservedSeats).isEqualTo(2);
            Assertions.assertThat(availableSeats).isEqualTo(1).withFailMessage(() -> "Invalid number of available seats");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Assertions.fail("Test Main Thread has been interrupted");
        }
    }

    private boolean checkIfSuccessfullyCreatedOrder(CompletableFuture<Void> result) throws InterruptedException {
        try {
            result.get();
            return true;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            Assertions.assertThat(cause).isInstanceOf(NoSuchQuantityException.class);
            return false;
        }
    }

    private CompletableFuture<Void> createOrderAsync(CountDownLatch startCountDownLatch, OrderRequestDto orderRequestDto, UserEntity userEntity, Executor executorService) {
        return CompletableFuture.runAsync(() -> {
            try {
                startCountDownLatch.countDown();
                boolean await = startCountDownLatch.await(3L, TimeUnit.SECONDS);

                if (!await)
                    throw new TimeoutException("Waiting for %s - out of time".formatted(Thread.currentThread().getName()));

                orderService.createOrder(orderRequestDto, userEntity);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Assertions.fail("Test Order Thread has been interrupted");
            } catch (TimeoutException e) {
                Assertions.fail("Test infrastructure failure");
            }
        }, executorService);
    }

    @Test
    @DisplayName("Should acquire Seat locks in consistent order for concurrent multi-event Orders")
    @Sql(scripts = {
            "classpath:scripts/sql/init_event.sql"
    })
    void shouldAcquireSeatLocksInConsistentOrderForConcurrentMultiEventOrders() {
        UserEntity userEntityA = new UserEntity("test1@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        UserEntity userEntityB = new UserEntity("test2@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        userRepository.save(userEntityA);
        userRepository.save(userEntityB);

        OrderRequestDto orderRequestDtoA = new OrderRequestDto(List.of(new TicketRequestDto(2L, 1), new TicketRequestDto(3L, 1)));
        OrderRequestDto orderRequestDtoB = new OrderRequestDto(List.of(new TicketRequestDto(3L, 1), new TicketRequestDto(2L, 1)));

        CountDownLatch startCountDownLatch = new CountDownLatch(2);

        try (var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> resultA = createOrderAsync(startCountDownLatch, orderRequestDtoA, userEntityA, executorService);
            CompletableFuture<Void> resultB = createOrderAsync(startCountDownLatch, orderRequestDtoB, userEntityB, executorService);

            int failedOperations = 0;

            if (!checkIfSuccessfullyCreatedOrder(resultA)) failedOperations++;
            if (!checkIfSuccessfullyCreatedOrder(resultB)) failedOperations++;

            Assertions.assertThat(failedOperations).isEqualTo(1);

            long count = orderRepository.count();
            Assertions.assertThat(count).isEqualTo(1);

            int reservedSeatForEvent_2 = seatRepository.countByEvent_EventIdAndSeatStatus(2L, SeatStatus.LOCKED_FOR_CHECKOUT);
            int reservedSeatForEvent_3 = seatRepository.countByEvent_EventIdAndSeatStatus(3L, SeatStatus.LOCKED_FOR_CHECKOUT);
            Assertions.assertThat(reservedSeatForEvent_2).isEqualTo(1);
            Assertions.assertThat(reservedSeatForEvent_3).isEqualTo(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Assertions.fail("Test Main Thread has been interrupted");
        }
    }

    @Test
    @DisplayName("Should keep Order pending and Seats locked when Order has not expired")
    @Sql(scripts = {
            "classpath:scripts/sql/init_event.sql",
    })
    void shouldKeepOrderPendingWhenOrderHasNotExpired() {
        int seatsReserve = 3;
        Long eventId = 1L;

        UserEntity userEntityA = new UserEntity("test1@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        userRepository.save(userEntityA);

        OrderRequestDto orderRequestDtoA = new OrderRequestDto(List.of(new TicketRequestDto(eventId, seatsReserve))); // NEW ORDER
        OrderResponseDto order = orderService.createOrder(orderRequestDtoA, userEntityA);

        OrderEntity resultValidateOrder = null;
        resultValidateOrder = orderTransactionService.validateOrderBefore(order.orderID(), userEntityA);

        int seatsCount = seatRepository.countByEvent_EventIdAndSeatStatus(1L, SeatStatus.LOCKED_FOR_CHECKOUT);

        Assertions.assertThat(resultValidateOrder.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(resultValidateOrder.getPaymentStatus()).isEqualTo(PaymentStatus.NOT_INITIALIZED);
        Assertions.assertThat(seatsReserve).isEqualTo(seatsCount);
    }

    @Test
    @DisplayName("Should expire Order and release Seats when payment flow detects an expired Order")
    @Sql(scripts = {
            "classpath:scripts/sql/init_event.sql",
    })
    void shouldExpireOrderAndReleaseSeatsWhenPaymentFlowDetectsExpiredOrder() {
        int seatsReserve = 3;
        Long eventId = 1L;

        UserEntity userEntityA = new UserEntity("test1@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        userRepository.save(userEntityA);

        OrderRequestDto orderRequestDtoA = new OrderRequestDto(List.of(new TicketRequestDto(eventId, seatsReserve))); // NEW ORDER
        OrderResponseDto order = orderService.createOrder(orderRequestDtoA, userEntityA);
        OrderEntity orderEntity = orderRepository.findById(order.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order.orderID())));
        orderEntity.setExpiresAt(Instant.now().minus(Duration.ofMinutes(30))); // TX
        orderRepository.save(orderEntity); // orderEntity = DETACHED

        Assertions.assertThatThrownBy(() -> orderTransactionService.validateOrderBefore(orderEntity.getOrderId(), userEntityA))
                .isInstanceOf(InitializationPaymentException.class)
                .hasMessageContaining("expired");

        OrderEntity orderEntityAfterValidation = orderRepository.findById(order.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order.orderID())));
        Assertions.assertThat(orderEntityAfterValidation.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
        Assertions.assertThat(orderEntityAfterValidation.getPaymentStatus()).isEqualTo(PaymentStatus.NOT_INITIALIZED);

        int freeSeatsCount = seatRepository.countByEvent_EventIdAndSeatStatus(1L, SeatStatus.AVAILABLE);
        Assertions.assertThat(seatsReserve).isEqualTo(freeSeatsCount);
    }

    @Test
    @DisplayName("Should fail payment state update when Order cannot be claimed for update")
    @Sql(scripts = {
            "classpath:scripts/sql/init_event.sql",
    })
    void shouldFailPaymentStateUpdateWhenOrderCannotBeClaimedForUpdate() {
        int seatsReserve = 3;
        Long eventId = 1L;

        UserEntity userEntityA = new UserEntity("test1@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        userRepository.save(userEntityA);

        OrderRequestDto orderRequestDtoA = new OrderRequestDto(List.of(new TicketRequestDto(eventId, seatsReserve))); // NEW ORDER
        OrderResponseDto order = orderService.createOrder(orderRequestDtoA, userEntityA);
        OrderEntity orderEntity = orderRepository.findById(order.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order.orderID())));

        CountDownLatch acquire = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (
                ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        ) {
            CompletableFuture<Void> holderResult = CompletableFuture.runAsync(() -> {
                transactionTestUtil.beginAndHoldTransaction(
                        () -> orderRepository.findByOrderIdWithLockingNoWait(orderEntity.getOrderId()),
                        acquire,
                        release
                );
            }, executorService);

            CompletableFuture<Void> validateResult = CompletableFuture.runAsync(() -> {
                try {
                    acquire.countDown();
                    boolean await = acquire.await(5, TimeUnit.SECONDS);
                    if (!await) throw new TimeoutException();
                    orderTransactionService.tryUpdateOrderEntityAfterPaymentInitialization(orderEntity.getOrderId(), "pi_test_id", Instant.now());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assertions.fail("Test OrderTransactionService Thread has been interrupted");
                } catch (TimeoutException e) {
                    Assertions.fail("Test application infrastructure failure");
                } finally {
                    release.countDown();
                }
            }, executorService);

            int failedLockOperationCount = 0;

            try {
                holderResult.join();
            } catch (CompletionException primaryEx) {
                Assertions.fail("Holder operation cannot throw exception");
            }

            try {
                validateResult.join();
            } catch (CompletionException primaryEx) {
                Throwable cause = primaryEx.getCause();

                if (cause instanceof CannotAcquireLockException) failedLockOperationCount++;
                else
                    Assertions.fail("Expected exception that is instance of CannotAcquireLockException. Exception: " + cause.getClass().getSimpleName());
            }

            Assertions.assertThat(failedLockOperationCount).isEqualTo(1);
            OrderEntity orderEntityAfter = orderRepository.findById(order.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order.orderID())));
            Assertions.assertThat(orderEntityAfter.getStripePaymentIntentId()).isNull();
            Assertions.assertThat(orderEntityAfter.getPaymentInitializedAt()).isNull();
            Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.NOT_INITIALIZED);
        }
    }


    @Test
    @DisplayName("Should skip locked Order and clean other expired Orders")
    @Sql(scripts = {
            "classpath:scripts/sql/init_event.sql",
    })
    void shouldSkipLockedOrderAndCleanOtherExpiredOrders() {
        UserEntity userEntityA = new UserEntity("testA@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        UserEntity userEntityB = new UserEntity("testB@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        UserEntity userEntityC = new UserEntity("testC@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        userRepository.save(userEntityA);
        userRepository.save(userEntityB);
        userRepository.save(userEntityC);

        OrderRequestDto orderRequestDtoA = new OrderRequestDto(List.of(new TicketRequestDto(1L, 3))); // NEW ORDER
        OrderResponseDto order1 = orderService.createOrder(orderRequestDtoA, userEntityA);
        OrderEntity reservedOrderEntity1 = orderRepository.findById(order1.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order1.orderID())));
        reservedOrderEntity1.setExpiresAt(Instant.now().minus(Duration.ofMinutes(30)));

        OrderRequestDto orderRequestDtoB = new OrderRequestDto(List.of(new TicketRequestDto(2L, 1))); // NEW ORDER
        OrderResponseDto order2 = orderService.createOrder(orderRequestDtoB, userEntityB);
        OrderEntity releaseOrderEntity2 = orderRepository.findById(order2.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order2.orderID())));
        releaseOrderEntity2.setExpiresAt(Instant.now().minus(Duration.ofMinutes(30)));

        OrderRequestDto orderRequestDtoC = new OrderRequestDto(List.of(new TicketRequestDto(3L, 1))); // NEW ORDER
        OrderResponseDto order3 = orderService.createOrder(orderRequestDtoC, userEntityC);
        OrderEntity releaseOrderEntity3 = orderRepository.findById(order3.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order3.orderID())));
        releaseOrderEntity3.setExpiresAt(Instant.now().minus(Duration.ofMinutes(30)));

        orderRepository.save(reservedOrderEntity1);
        orderRepository.save(releaseOrderEntity2);
        orderRepository.save(releaseOrderEntity3);


        int lockedSeatsForOrderEntity1BeforeClean = seatRepository.countByEvent_EventIdAndSeatStatus(1L, SeatStatus.LOCKED_FOR_CHECKOUT); // 3
        int lockedSeatsForOrderEntity2BeforeClean = seatRepository.countByEvent_EventIdAndSeatStatus(2L, SeatStatus.LOCKED_FOR_CHECKOUT); // 1
        int lockedSeatsForOrderEntity3BeforeClean = seatRepository.countByEvent_EventIdAndSeatStatus(3L, SeatStatus.LOCKED_FOR_CHECKOUT); // 1

        Assertions.assertThat(lockedSeatsForOrderEntity1BeforeClean).isEqualTo(3);
        Assertions.assertThat(lockedSeatsForOrderEntity2BeforeClean).isEqualTo(1);
        Assertions.assertThat(lockedSeatsForOrderEntity3BeforeClean).isEqualTo(1);


        CountDownLatch acquire = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (
                ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        ) {
            CompletableFuture<Void> holderResult = CompletableFuture.runAsync(() -> {
                transactionTestUtil.beginAndHoldTransaction(
                        () -> {
                            orderRepository.findByOrderIdWithLockingNoWait(reservedOrderEntity1.getOrderId());
                        },
                        acquire,
                        release
                );
            }, executorService);

            CompletableFuture<Void> cleanerResult = CompletableFuture.runAsync(() -> {
                try {
                    acquire.countDown();
                    boolean await = acquire.await(5, TimeUnit.SECONDS);
                    if (!await) throw new TimeoutException();
                    orderExpirationCleaner.scanAndCleanExpiredOrders(); // BATCH 50 ORDERS - defined in [TEST PROPERTIES]
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assertions.fail("Test OrderTransactionService Thread has been interrupted");
                } catch (TimeoutException e) {
                    Assertions.fail("Test application infrastructure failure");
                } finally {
                    release.countDown();
                }
            }, executorService);

            holderResult.join();
            cleanerResult.join();

            int freeForOrderEntity1AfterClean = seatRepository.countByEvent_EventIdAndSeatStatus(1L, SeatStatus.AVAILABLE); // 0
            int freeForOrderEntity2AfterClean = seatRepository.countByEvent_EventIdAndSeatStatus(2L, SeatStatus.AVAILABLE); // 1
            int freeForOrderEntity3AfterClean = seatRepository.countByEvent_EventIdAndSeatStatus(3L, SeatStatus.AVAILABLE); // 1

            Assertions.assertThat(freeForOrderEntity1AfterClean).isEqualTo(0);
            Assertions.assertThat(freeForOrderEntity2AfterClean).isEqualTo(1);
            Assertions.assertThat(freeForOrderEntity3AfterClean).isEqualTo(1);

            // Refresh Entity State
            OrderEntity reservedOrderEntity1AfterClean = orderRepository.findById(order1.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order1.orderID())));
            OrderEntity releaseOrderEntity2AfterClean = orderRepository.findById(order2.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order2.orderID())));
            OrderEntity releaseOrderEntity3AfterClean = orderRepository.findById(order3.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order3.orderID())));

            Assertions.assertThat(reservedOrderEntity1AfterClean.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
            Assertions.assertThat(releaseOrderEntity2AfterClean.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
            Assertions.assertThat(releaseOrderEntity3AfterClean.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
        }
    }

    @Test
    @DisplayName("Should interrupt payment flow when Order cannot be claimed for expiration check")
    @Sql(scripts = {
            "classpath:scripts/sql/init_event.sql",
    })
    void shouldInterruptPaymentFlowWhenOrderCannotBeClaimedForExpirationCheck() {
        int seatsReserve = 3;
        Long eventId = 1L;

        UserEntity userEntityA = new UserEntity("test1@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        userRepository.save(userEntityA);

        OrderRequestDto orderRequestDtoA = new OrderRequestDto(List.of(new TicketRequestDto(eventId, seatsReserve))); // NEW ORDER
        OrderResponseDto order = orderService.createOrder(orderRequestDtoA, userEntityA);
        OrderEntity orderEntity = orderRepository.findById(order.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order.orderID())));
        orderEntity.setExpiresAt(Instant.now().minus(Duration.ofMinutes(30)));
        orderRepository.save(orderEntity);
        CountDownLatch acquire = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (
                ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        ) {
            CompletableFuture<Void> holderResult = CompletableFuture.runAsync(() -> {
                transactionTestUtil.beginAndHoldTransaction(
                        () -> orderRepository.findByOrderIdWithLockingNoWait(orderEntity.getOrderId()),
                        acquire,
                        release
                );
            }, executorService);

            CompletableFuture<Void> validateResult = CompletableFuture.runAsync(() -> {
                try {
                    acquire.countDown();
                    boolean await = acquire.await(5, TimeUnit.SECONDS);
                    if (!await) throw new TimeoutException();
                    orderTransactionService.validateOrderBefore(orderEntity.getOrderId(), userEntityA);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assertions.fail("Test OrderTransactionService Thread has been interrupted");
                } catch (TimeoutException e) {
                    Assertions.fail("Test application infrastructure failure");
                } finally {
                    release.countDown();
                }
            }, executorService);

            int failedLockOperationCount = 0;

            try {
                holderResult.join();
            } catch (CompletionException primaryEx) {
                Assertions.fail("Holder operation cannot throw exception");
            }

            try {
                validateResult.join();
            } catch (CompletionException primaryEx) {
                Throwable cause = primaryEx.getCause();

                if (cause instanceof InitializationPaymentException && cause.getCause() instanceof ConcurrencyClaimException)
                    failedLockOperationCount++;
                else
                    Assertions.fail("Expected exception that is instance of InitializationPaymentException a caused by: ConcurrencyClaimException. Exception: %s, caused: %s".formatted(cause.getClass().getSimpleName(), cause.getCause()));
            }

            Assertions.assertThat(failedLockOperationCount).isEqualTo(1);

            OrderEntity orderEntityAfter = orderRepository.findById(order.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order.orderID())));
            int seatsCount = seatRepository.countByEvent_EventIdAndSeatStatus(eventId, SeatStatus.LOCKED_FOR_CHECKOUT);
            Assertions.assertThat(seatsCount).isEqualTo(seatsReserve);

            Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
            Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.NOT_INITIALIZED);
        }
    }

    @Test
    @DisplayName("Should persist payment initialization state after successful TX2 update")
    @Sql(scripts = {
            "classpath:scripts/sql/init_event.sql",
    })
    void shouldPersistPaymentInitializationStateAfterSuccessfulUpdate() {
        int seatsReserve = 3;
        Long eventId = 1L;

        String paymentIntentId = "pi_123";

        UserEntity userEntityA = new UserEntity("test1@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        userRepository.save(userEntityA);

        OrderRequestDto orderRequestDtoA = new OrderRequestDto(List.of(new TicketRequestDto(eventId, seatsReserve))); // NEW ORDER
        OrderResponseDto order = orderService.createOrder(orderRequestDtoA, userEntityA);
        OrderEntity orderEntity = orderRepository.findById(order.orderID()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order.orderID())));

        orderTransactionService.tryUpdateOrderEntityAfterPaymentInitialization(orderEntity.getOrderId(), paymentIntentId, Instant.now());

        orderEntity = orderRepository.findById(orderEntity.getOrderId()).orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(order.orderID())));

        Assertions.assertThat(orderEntity.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        Assertions.assertThat(orderEntity.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntity.getStripePaymentIntentId()).isEqualTo(paymentIntentId);
        Assertions.assertThat(orderEntity.getPaymentInitializedAt()).isNotNull();
    }
}

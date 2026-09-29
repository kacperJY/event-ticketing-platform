package pl.kacper.sales_api.domain.order.stripe.refund;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.jdbc.Sql;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.domain.BaseIT;
import pl.kacper.sales_api.domain.order.*;
import pl.kacper.sales_api.domain.order.dto.OrderRequestDto;
import pl.kacper.sales_api.domain.order.dto.OrderResponseDto;
import pl.kacper.sales_api.domain.order.dto.TicketRequestDto;
import pl.kacper.sales_api.domain.user.UserEntity;
import pl.kacper.sales_api.domain.user.UserRepository;
import pl.kacper.sales_api.utils.TransactionTestUtil;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RefundInitializerTransactionServiceIT extends BaseIT {

    private final RefundInitializerTransactionService refundInitializerTransactionService;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final OrderService orderService;
    private final TransactionTestUtil transactionTestUtil;

    @Autowired
    RefundInitializerTransactionServiceIT(RefundInitializerTransactionService refundInitializerTransactionService, OrderRepository orderRepository,
                                          UserRepository userRepository, OrderService orderService, TransactionTestUtil transactionTestUtil) {
        this.refundInitializerTransactionService = refundInitializerTransactionService;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.orderService = orderService;
        this.transactionTestUtil = transactionTestUtil;
    }

    @Sql(scripts = "classpath:scripts/sql/init_event.sql")
    @Test
    void shouldReturnOrderRefundCandidates() {
        UserEntity user = createRefundCandidateTestUser();
        OrderEntity orderA = createRefundCandidate(user,
                order -> order.setStripePaymentIntentId("pi_A"));
        OrderEntity orderB = createRefundCandidate(user,
                order -> order.setStripePaymentIntentId("pi_B"));
        UUID orderIdA = orderA.getOrderId();
        UUID orderIdB = orderB.getOrderId();

        List<OrderEntity> orderRefundCandidates = refundInitializerTransactionService.findOrderRefundCandidates(10);

        Assertions.assertThat(orderRefundCandidates)
                .extracting(OrderEntity::getOrderId)
                .containsExactlyInAnyOrder(orderIdA, orderIdB);

        assertEligibleRefundCandidateUnchanged(orderIdA, "pi_A");
        assertEligibleRefundCandidateUnchanged(orderIdB, "pi_B");
    }

    @Sql(scripts = "classpath:scripts/sql/init_event.sql")
    @Test
    void shouldReturnOrderRefundCandidateThatAreNotClaimed() {
        UserEntity user = createRefundCandidateTestUser();
        OrderEntity orderA = createRefundCandidate(user,
                order -> order.setStripePaymentIntentId("pi_A"));
        OrderEntity orderB = createRefundCandidate(user,
                order -> order.setStripePaymentIntentId("pi_B"));
        UUID orderIdA = orderA.getOrderId();
        UUID orderIdB = orderB.getOrderId();


        CountDownLatch acquire = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        List<OrderEntity> orderRefundCandidates = null;
        try (
                ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        ) {
            CompletableFuture<Void> holder = CompletableFuture.runAsync(() ->
                            transactionTestUtil.beginAndHoldTransaction(
                                    () -> orderRepository.findByOrderIdWithLockingNoWait(orderIdA),
                                    acquire,
                                    release
                            ),
                    executorService
            );
            CompletableFuture<List<OrderEntity>> runner = CompletableFuture.supplyAsync(
                    () -> {
                        List<OrderEntity> refundCandidates = null;
                        try {
                            acquire.countDown();
                            boolean awaitFlag = acquire.await(5, TimeUnit.SECONDS);
                            if (!awaitFlag)
                                throw new TimeoutException("Out of time waiting for Test Runner Thread. Application infrastructure failure.");
                            refundCandidates = refundInitializerTransactionService.findOrderRefundCandidates(10);

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            Assertions.fail("Test Runner Thread has been interrupted while waiting");
                        } catch (TimeoutException e) {
                            Assertions.fail(e.getMessage());
                        } finally {
                            release.countDown();
                        }
                        return refundCandidates;
                    },
                    executorService
            );

            holder.join();
            orderRefundCandidates = runner.join();
        }

        Assertions.assertThat(orderRefundCandidates)
                .extracting(OrderEntity::getOrderId)
                .containsExactly(orderIdB);

        assertEligibleRefundCandidateUnchanged(orderIdB, "pi_B");
    }

    // Each exclusion case changes one query condition only.
    // Some combinations deliberately violate domain invariants to verify the filter.

    @Sql(scripts = "classpath:scripts/sql/init_event.sql")
    @ParameterizedTest(name = "OrderStatus={0} is excluded")
    @EnumSource(
            value = OrderStatus.class,
            names = "EXPIRED",
            mode = EnumSource.Mode.EXCLUDE
    )
    void shouldExcludeOrdersThatAreNotExpired(OrderStatus orderStatus) {
        assertExcludedCandidateDoesNotHideEligibleOrder(
                order -> order.setOrderStatus(orderStatus)
        );
    }

    @Sql(scripts = "classpath:scripts/sql/init_event.sql")
    @ParameterizedTest(name = "PaymentStatus={0} is excluded")
    @EnumSource(
            value = PaymentStatus.class,
            names = "REFUND_REQUIRED",
            mode = EnumSource.Mode.EXCLUDE
    )
    void shouldExcludeOrdersThatDoNotRequireRefund(PaymentStatus paymentStatus) {
        assertExcludedCandidateDoesNotHideEligibleOrder(
                order -> order.setPaymentStatus(paymentStatus)
        );
    }

    @Sql(scripts = "classpath:scripts/sql/init_event.sql")
    @Test
    void shouldExcludeOrderWithoutPaymentIntentId() {
        assertExcludedCandidateDoesNotHideEligibleOrder(
                order -> order.setStripePaymentIntentId(null)
        );
    }

    @Sql(scripts = "classpath:scripts/sql/init_event.sql")
    @Test
    void shouldExcludeOrderWithRefundId() {
        assertExcludedCandidateDoesNotHideEligibleOrder(
                order -> order.setStripeRefundId("re_existing")
        );
    }

    @Sql(scripts = "classpath:scripts/sql/init_event.sql")
    @Test
    void shouldExcludeOrderWithRefundedAt() {
        assertExcludedCandidateDoesNotHideEligibleOrder(
                order -> order.setRefundedAt(Instant.parse("2026-09-01T12:00:00Z"))
        );
    }

    @Sql(scripts = "classpath:scripts/sql/init_event.sql")
    @Test
    void shouldExcludeOrderWithRefundRequestedAt() {
        assertExcludedCandidateDoesNotHideEligibleOrder(
                order -> order.setRefundRequestedAt(Instant.parse("2026-09-01T12:00:00Z"))
        );
    }

    @Sql(scripts = "classpath:scripts/sql/init_event.sql")
    @ParameterizedTest(name = "batchSize={0}, eligible orders=3")
    @ValueSource(ints = {1, 2, 3, 4})
    void shouldRespectBatchSize(int batchSize) {
        UserEntity user = createRefundCandidateTestUser();

        // Event 1 has three seats; each order reserves one of them.
        OrderEntity orderA = createRefundCandidate(user);
        OrderEntity orderB = createRefundCandidate(user);
        OrderEntity orderC = createRefundCandidate(user);

        List<UUID> eligibleOrderIds = List.of(
                orderA.getOrderId(), orderB.getOrderId(), orderC.getOrderId()
        );

        List<OrderEntity> candidates =
                refundInitializerTransactionService.findOrderRefundCandidates(batchSize);

        Assertions.assertThat(candidates)
                .extracting(OrderEntity::getOrderId)
                .hasSize(Math.min(batchSize, eligibleOrderIds.size()))
                .doesNotHaveDuplicates()
                .isSubsetOf(eligibleOrderIds);
    }

    @Test
    void shouldReturnEmptyListWhenNoOrdersExist() {
        List<OrderEntity> candidates =
                refundInitializerTransactionService.findOrderRefundCandidates(10);

        Assertions.assertThat(candidates).isEmpty();
    }

    @Sql(scripts = "classpath:scripts/sql/init_event.sql")
    @Test
    void shouldReturnEmptyListWhenNoOrdersQualify() {
        UserEntity user = createRefundCandidateTestUser();

        // A newly created order is PENDING / NOT_INITIALIZED.
        OrderResponseDto createdOrder = orderService.createOrder(
                new OrderRequestDto(List.of(new TicketRequestDto(1L, 1))),
                user
        );

        Assertions.assertThat(orderRepository.findById(createdOrder.orderID())).isPresent();

        List<OrderEntity> candidates =
                refundInitializerTransactionService.findOrderRefundCandidates(10);

        Assertions.assertThat(candidates).isEmpty();
    }

    // TX2: persist the result of Refund.create; Stripe is not called by these tests.

    @Sql(scripts = "classpath:scripts/sql/init_event.sql")
    @Test
    void shouldPersistRefundInitialization() {
        OrderEntity order = createRefundCandidate(createRefundCandidateTestUser());
        UUID orderId = order.getOrderId();
        String paymentIntentId = order.getStripePaymentIntentId();
        Instant before = Instant.now();

        refundInitializerTransactionService.validateAndUpdateAfterRefundCreate(orderId, "re_created");

        Instant after = Instant.now();
        RefundStateSnapshot persisted = readRefundState(orderId);
        Assertions.assertThat(persisted.orderStatus()).isEqualTo(OrderStatus.EXPIRED);
        Assertions.assertThat(persisted.paymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        Assertions.assertThat(persisted.paymentIntentId()).isEqualTo(paymentIntentId);
        Assertions.assertThat(persisted.refundId()).isEqualTo("re_created");
        Assertions.assertThat(persisted.refundedAt()).isNull();
        // Allow for the difference between Java and database timestamp precision.
        Assertions.assertThat(persisted.refundRequestedAt())
                .isNotNull()
                .isBetween(before.minusMillis(1), after.plusMillis(1));
    }

    @Sql(scripts = "classpath:scripts/sql/init_event.sql")
    @Test
    void shouldRejectRefundInitializationWhenOrderIsLockedWithoutChangingPersistedState() {
        OrderEntity order = createRefundCandidate(createRefundCandidateTestUser());
        UUID orderId = order.getOrderId();
        RefundStateSnapshot before = readRefundState(orderId);
        CountDownLatch acquire = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> holder = CompletableFuture.runAsync(
                    () -> transactionTestUtil.beginAndHoldTransaction(
                            () -> orderRepository.findByOrderIdWithLockingNoWait(orderId),
                            acquire,
                            release
                    ), executorService);

            CompletableFuture<Void> runner = CompletableFuture.runAsync(() -> {
                        try {
                            acquire.countDown();
                            boolean await = acquire.await(5, TimeUnit.SECONDS);
                            if (!await)
                                throw new TimeoutException("Out of time waiting for Test Runner Thread. Application infrastructure failure.");
                            refundInitializerTransactionService.validateAndUpdateAfterRefundCreate(orderId, "re_created");

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            Assertions.fail("Test Runner Thread has been interrupted while waiting");
                        } catch (TimeoutException e) {
                            Assertions.fail(e.getMessage());
                        } finally {
                            release.countDown();
                        }
                    }, executorService
            );
            holder.join();
            Assertions.assertThatThrownBy(runner::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(CannotAcquireLockException.class);


            Assertions.assertThat(readRefundState(orderId)).isEqualTo(before);
        }
    }

    private RefundStateSnapshot readRefundState(UUID orderId) {
        // Read after the service transaction has completed, through a separate repository call.
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException(
                        "Order with ID=%s does not exist".formatted(orderId)));
        return new RefundStateSnapshot(
                order.getOrderStatus(), order.getPaymentStatus(), order.getStripePaymentIntentId(),
                order.getStripeRefundId(), order.getRefundRequestedAt(), order.getRefundedAt());
    }

    private record RefundStateSnapshot(
            OrderStatus orderStatus,
            PaymentStatus paymentStatus,
            String paymentIntentId,
            String refundId,
            Instant refundRequestedAt,
            Instant refundedAt
    ) {
    }

    private void assertExcludedCandidateDoesNotHideEligibleOrder(
            Consumer<OrderEntity> disqualify
    ) {
        UserEntity user = createRefundCandidateTestUser();
        createRefundCandidate(user, disqualify);
        OrderEntity eligibleOrder = createRefundCandidate(user);

        List<OrderEntity> candidates =
                refundInitializerTransactionService.findOrderRefundCandidates(10);

        Assertions.assertThat(candidates)
                .extracting(OrderEntity::getOrderId)
                .containsExactly(eligibleOrder.getOrderId());
    }

    private UserEntity createRefundCandidateTestUser() {
        return userRepository.save(
                new UserEntity("refund-candidate@gmail.com", "Password123", "TestFirstname", "TestLastname")
        );
    }

    private void assertEligibleRefundCandidateUnchanged(UUID orderId, String paymentIntentId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException(
                        "Order with ID=%s does not exist".formatted(orderId)
                ));

        Assertions.assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.EXPIRED);
        Assertions.assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_REQUIRED);
        Assertions.assertThat(order.getRefundRequestedAt()).isNull();
        Assertions.assertThat(order.getRefundedAt()).isNull();
        Assertions.assertThat(order.getStripeRefundId()).isNull();
        Assertions.assertThat(order.getStripePaymentIntentId()).isEqualTo(paymentIntentId);
    }

    private OrderEntity createRefundCandidate(UserEntity user) {
        return createRefundCandidate(user, order -> {
        });
    }

    private OrderEntity createRefundCandidate(UserEntity user, Consumer<OrderEntity> customize) {
        OrderResponseDto createdOrder = orderService.createOrder(
                new OrderRequestDto(List.of(new TicketRequestDto(1L, 1))),
                user
        );
        UUID orderId = createdOrder.orderID();

        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException(
                        "Order with ID=%s does not exist".formatted(orderId)
                ));

        order.setOrderStatus(OrderStatus.EXPIRED);
        order.setPaymentStatus(PaymentStatus.REFUND_REQUIRED);
        order.setStripePaymentIntentId("pi_" + orderId);
        order.setStripeRefundId(null);
        order.setRefundRequestedAt(null);
        order.setRefundedAt(null);

        // Apply case-specific overrides after eligible defaults and before persistence.
        customize.accept(order);

        return orderRepository.save(order);
    }
}

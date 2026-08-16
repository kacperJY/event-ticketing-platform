package pl.kacper.sales_api.domain.order;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import pl.kacper.sales_api.common.exception.ConcurrencyClaimException;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.common.exception.paymentException.InitializationPaymentException;
import pl.kacper.sales_api.common.exception.paymentException.PaymentIntentStateMismatchException;
import pl.kacper.sales_api.domain.user.UserEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
public class OrderTransactionServiceTest {


    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderExpirationCleaner orderExpirationCleaner;

    @InjectMocks
    private OrderTransactionService orderTransactionService;


    // #1 - Common tests
    @Test
    @DisplayName("Should reject user validation when Order expiration cannot be checked due to a concurrency claim")
    void shouldValidateOrderBeforeTranslateConcurrencyExceptionToPaymentException() {
        UUID orderId = UUID.randomUUID();

        Mockito.doThrow(ConcurrencyClaimException.class).when(orderExpirationCleaner).checkIfOrderExpiresTimePastAndClean(orderId);

        Assertions.assertThatThrownBy(() -> orderTransactionService.validateOrderBefore(orderId, null))
                .isInstanceOf(InitializationPaymentException.class);

        Mockito.verify(orderRepository, Mockito.times(0)).findOrderEntityWithUserByOrderId(orderId);
    }

    @Test
    @DisplayName("Should reject user validation when Order does not exist")
    void shouldValidateOrderWithUserThrowExceptionWhenOrderDoesNotExistBefore() {
        UUID orderId = UUID.randomUUID();

        Mockito.when(orderRepository.findOrderEntityWithUserByOrderId(orderId)).thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() -> orderTransactionService.validateOrderBefore(orderId, null))
                .isInstanceOf(NoSuchDbRecordException.class);
    }

    @Test
    @DisplayName("Should reject post-retrieve validation when Order expiration cannot be checked due to a concurrency claim")
    void shouldValidateOrderAfterRetrieveTranslateConcurrencyExceptionToPaymentException() {
        UUID orderId = UUID.randomUUID();

        Mockito.doThrow(ConcurrencyClaimException.class).when(orderExpirationCleaner).checkIfOrderExpiresTimePastAndClean(orderId);

        Assertions.assertThatThrownBy(() -> orderTransactionService.validateOrderAfterRetrieve(orderId))
                .isInstanceOf(InitializationPaymentException.class);

        Mockito.verify(orderRepository, Mockito.times(0)).findOrderEntityWithUserByOrderId(orderId);
    }

    @Test
    @DisplayName("Should reject post-retrieve validation when Order does not exist")
    void shouldValidateOrderAfterRetrieveThrowExceptionWhenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();

        Mockito.when(orderRepository.findOrderEntityWithUserByOrderId(orderId)).thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() -> orderTransactionService.validateOrderAfterRetrieve(orderId))
                .isInstanceOf(NoSuchDbRecordException.class);
    }

    @Test
    @DisplayName("Should reject PaymentIntent persistence when Order expiration cannot be checked due to a concurrency claim")
    void shouldTryUpdateOrderEntityAfterPaymentInitializationTranslateConcurrencyExceptionToPaymentException() {
        UUID orderId = UUID.randomUUID();
        String paymentIntentId = "123";
        Instant now = Instant.now();

        Mockito.doThrow(ConcurrencyClaimException.class).when(orderExpirationCleaner).checkIfOrderExpiresTimePastAndClean(orderId);

        Assertions.assertThatThrownBy(() -> orderTransactionService.tryUpdateOrderEntityAfterPaymentInitialization(orderId, paymentIntentId, now))
                .isInstanceOf(InitializationPaymentException.class);

        Mockito.verify(orderRepository, Mockito.times(0)).findByOrderIdWithLockingNoWait(orderId);
    }

    @Test
    @DisplayName("Should reject PaymentIntent persistence when Order does not exist")
    void shouldTryUpdateOrderEntityAfterPaymentInitializationThrowExceptionWhenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();
        String paymentId = "123";
        Instant now = Instant.now();

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId)).thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() -> orderTransactionService.tryUpdateOrderEntityAfterPaymentInitialization(orderId, paymentId, now))
                .isInstanceOf(NoSuchDbRecordException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"PENDING"})
    @DisplayName("Should reject every OrderStatus other than PENDING")
    void shouldValidateOrderThrowExceptionWhenOrderStatusIsDifferentThanPending(OrderStatus orderStatus) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(orderStatus);
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);

        Assertions.assertThatThrownBy(() -> orderTransactionService.validateOrder(orderEntity))
                .isInstanceOf(InitializationPaymentException.class)
                .hasMessageContaining("order");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"PENDING", "NOT_INITIALIZED"})
    @DisplayName("Should reject every PaymentStatus other than PENDING or NOT_INITIALIZED")
    void shouldValidateOrderThrowExceptionWhenPaymentStatusIsDifferentThanPendingOrNotInitialized(PaymentStatus paymentStatus) {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(paymentStatus);

        Assertions.assertThatThrownBy(() -> orderTransactionService.validateOrder(orderEntity))
                .isInstanceOf(InitializationPaymentException.class)
                .hasMessageContaining("payment");
    }

    // #2 Unique tests
    @Test
    @DisplayName("Should deny payment initialization when authenticated user is not the Order purchaser")
    void shouldValidateOrderWithUserThrowExceptionWhenUserBeforeIsNotPurchaser() {
        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);

        UserEntity orderPurchaser = new UserEntity("correct@email", null, null, null);
        orderEntity.setPurchaser(orderPurchaser);

        UserEntity otherUser = new UserEntity("invalid@email", null, null, null);

        Mockito.when(orderRepository.findOrderEntityWithUserByOrderId(orderId)).thenReturn(Optional.of(orderEntity));

        Assertions.assertThatThrownBy(() -> orderTransactionService.validateOrderBefore(orderId, otherUser))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Should reject returning an existing PaymentIntent when local PaymentStatus is NOT_INITIALIZED")
    void shouldValidateOrderAfterRetrieveWhenPaymentStatusIsNotInitialized() {
        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED); // invalid PaymentStatus in flow

        Mockito.when(orderRepository.findOrderEntityWithUserByOrderId(orderId)).thenReturn(Optional.of(orderEntity));

        Assertions.assertThatThrownBy(() -> orderTransactionService.validateOrderAfterRetrieve(orderId))
                .isInstanceOf(InitializationPaymentException.class);
    }

    @Test
    @DisplayName("Should reject Order before payment initialization when PaymentStatus is PENDING but PaymentIntent ID is missing")
    void shouldRejectOrderWhenPaymentIsPendingAndPaymentIntentIdIsNull() {
        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);

        UserEntity userEntity = new UserEntity("email@test", null, null, null);
        orderEntity.setPurchaser(userEntity);

        Mockito.when(orderRepository.findOrderEntityWithUserByOrderId(orderId))
                .thenReturn(Optional.of(orderEntity));

        Assertions.assertThatThrownBy(() ->
                        orderTransactionService.validateOrderBefore(orderId, userEntity))
                .isInstanceOf(PaymentIntentStateMismatchException.class);
    }

    @Test
    @DisplayName("Should reject Order before payment initialization when PaymentStatus is NOT_INITIALIZED but PaymentIntent ID already exists")
    void shouldRejectOrderWhenPaymentIsNotInitializedAndPaymentIntentIdExists() {
        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);
        orderEntity.setStripePaymentIntentId("pi_123");

        UserEntity userEntity = new UserEntity("email@test", null, null, null);
        orderEntity.setPurchaser(userEntity);

        Mockito.when(orderRepository.findOrderEntityWithUserByOrderId(orderId))
                .thenReturn(Optional.of(orderEntity));

        Assertions.assertThatThrownBy(() ->
                        orderTransactionService.validateOrderBefore(orderId, userEntity))
                .isInstanceOf(PaymentIntentStateMismatchException.class);
    }

    @Test
    @DisplayName("Should detect inconsistent state when NOT_INITIALIZED payment already has a PaymentIntent ID")
    void shouldTryUpdateOrderEntityAfterPaymentInitializationThrowsExceptionWhenPaymentStatusNotInitializedAndPaymentIdNotNull() {
        String paymentId = "123";
        Instant now = Instant.now();

        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);
        orderEntity.setStripePaymentIntentId(paymentId);


        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId)).thenReturn(Optional.of(orderEntity));

        Assertions.assertThatThrownBy(() -> orderTransactionService.tryUpdateOrderEntityAfterPaymentInitialization(orderId, paymentId, now))
                .isInstanceOf(PaymentIntentStateMismatchException.class);
    }

    @Test
    @DisplayName("Should detect inconsistent state when PENDING payment has no PaymentIntent ID")
    void shouldTryUpdateOrderEntityAfterPaymentInitializationThrowsExceptionWhenPaymentStatusPendingAndPaymentIdIsNull() {
        String paymentId = "123";
        Instant now = Instant.now();

        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderEntity.setStripePaymentIntentId(null);


        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId)).thenReturn(Optional.of(orderEntity));

        Assertions.assertThatThrownBy(() -> orderTransactionService.tryUpdateOrderEntityAfterPaymentInitialization(orderId, paymentId, now))
                .isInstanceOf(PaymentIntentStateMismatchException.class);
    }

    @Test
    @DisplayName("Should detect mismatch when stored and incoming PaymentIntent IDs differ")
    void shouldTryUpdateOrderEntityAfterPaymentInitializationThrowsExceptionWhenPaymentStatusPendingAndPaymentIdIsDifferentThanIncoming() {
        String paymentId = "123";
        String incoming = "321";
        Instant now = Instant.now();

        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderEntity.setStripePaymentIntentId(paymentId);


        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId)).thenReturn(Optional.of(orderEntity));

        Assertions.assertThatThrownBy(() -> orderTransactionService.tryUpdateOrderEntityAfterPaymentInitialization(orderId, incoming, now))
                .isInstanceOf(PaymentIntentStateMismatchException.class);
    }

    // #3 Happy-path tests
    @Test
    @DisplayName("Should validate a PENDING Order with NOT_INITIALIZED payment successfully")
    void shouldValidateOrderExecuteSuccessfully() {
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);

        orderTransactionService.validateOrder(orderEntity);
    }

    @Test
    @DisplayName("Should validate a retrieved PENDING PaymentIntent successfully")
    void shouldValidateOrderAfterRetrieveExecuteSuccessfully() {
        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);

        Mockito.when(orderRepository.findOrderEntityWithUserByOrderId(orderId)).thenReturn(Optional.of(orderEntity));

        orderTransactionService.validateOrderAfterRetrieve(orderId);
    }

    @Test
    @DisplayName("Should return Order when authenticated user is the purchaser and Order state is valid")
    void shouldValidateOrderBeforeExecuteSuccessfully() {
        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);

        UserEntity orderPurchaser = new UserEntity("correct@email", null, null, null);
        orderEntity.setPurchaser(orderPurchaser);

        Mockito.when(orderRepository.findOrderEntityWithUserByOrderId(orderId)).thenReturn(Optional.of(orderEntity));

        OrderEntity orderEntityAsResult = orderTransactionService.validateOrderBefore(orderId, orderPurchaser);

        Assertions.assertThat(orderEntityAsResult).isEqualTo(orderEntity);
    }


    @Test
    @DisplayName("Should transition NOT_INITIALIZED payment to PENDING and persist PaymentIntent data")
    void shouldTryUpdateOrderEntityAfterPaymentInitializationExecuteSuccessfullyWhenPaymentStatusNotInitializedAndPaymentIdIsNull() {
        String paymentId = "123";
        Instant now = Instant.now();

        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);
        orderEntity.setStripePaymentIntentId(null);


        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId)).thenReturn(Optional.of(orderEntity));

        orderTransactionService.tryUpdateOrderEntityAfterPaymentInitialization(orderId, paymentId, now);

        Assertions.assertThat(orderEntity.getStripePaymentIntentId()).isEqualTo(paymentId);
        Assertions.assertThat(orderEntity.getPaymentInitializedAt()).isEqualTo(now);
        Assertions.assertThat(orderEntity.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("Should succeed idempotently when PENDING payment already contains the same PaymentIntent ID")
    void shouldTryUpdateOrderEntityAfterPaymentInitializationExecuteSuccessfullyWhenPaymentStatusPendingAndPaymentIdIsTheSameAsIncoming() {
        String paymentId = "123";
        String incoming = "123";
        Instant now = Instant.now();

        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderEntity.setStripePaymentIntentId(paymentId);
        orderEntity.setPaymentInitializedAt(now);

        Mockito.when(orderRepository.findByOrderIdWithLockingNoWait(orderId)).thenReturn(Optional.of(orderEntity));

        orderTransactionService.tryUpdateOrderEntityAfterPaymentInitialization(orderId, incoming, Instant.now());

        Assertions.assertThat(orderEntity.getStripePaymentIntentId()).isEqualTo(paymentId);
        Assertions.assertThat(orderEntity.getPaymentInitializedAt()).isEqualTo(now);
        Assertions.assertThat(orderEntity.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }
}
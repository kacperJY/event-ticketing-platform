package pl.kacper.sales_api.domain.order;

import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.service.PaymentIntentService;
import com.stripe.service.V1Services;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.common.exception.NoSuchQuantityException;
import pl.kacper.sales_api.common.exception.paymentException.ExternalPaymentServiceException;
import pl.kacper.sales_api.common.exception.paymentException.InitializationPaymentException;
import pl.kacper.sales_api.common.exception.paymentException.PaymentIntentStateMismatchException;
import pl.kacper.sales_api.domain.event.EventEntity;
import pl.kacper.sales_api.domain.event.EventRepository;
import pl.kacper.sales_api.domain.order.dto.OrderPaymentResponseDto;
import pl.kacper.sales_api.domain.order.dto.OrderRequestDto;
import pl.kacper.sales_api.domain.order.dto.OrderResponseDto;
import pl.kacper.sales_api.domain.seat.SeatEntity;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;
import pl.kacper.sales_api.domain.user.UserEntity;
import pl.kacper.sales_api.domain.user.UserRepository;

import java.time.Instant;
import java.util.*;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
public class OrderServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderTransactionService orderTransactionService;

    @Mock
    private StripeClient stripeClientMock;
    @Mock
    private V1Services v1ServicesMock;
    @Mock
    private PaymentIntentService paymentIntentServiceMock;

    @InjectMocks
    private OrderService orderService;


    private UserEntity userEntity;

    @BeforeEach
    void initializeState() {
        userEntity = new UserEntity("mail@gmail.com", "password", "Firstname", "Lastname");
    }

    @Test
    @DisplayName("Should throw UsernameNotFoundException when Order owner does not exist")
    void shouldThrowExceptionWhenOrderOwnerDoesNotExist() {
        OrderRequestDto orderRequestDto = new OrderRequestDto(1L, 3);

        Mockito.when(userRepository.findUserByEmail(userEntity.getEmail())).thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() -> orderService.createOrder(orderRequestDto, userEntity))
                .isInstanceOf(UsernameNotFoundException.class);

        Mockito.verifyNoInteractions(seatRepository, eventRepository, orderRepository);
    }

    @Test
    @DisplayName("Should reject missing Event before fetching or reserving Seats")
    void shouldThrowExceptionWhenEventDoesNotExist() {
        OrderRequestDto orderRequestDto = new OrderRequestDto(999L, 1);

        Mockito.when(userRepository.findUserByEmail(userEntity.getEmail())).thenReturn(Optional.of(userEntity));
        Mockito.when(eventRepository.existsById(999L)).thenReturn(false);

        Assertions.assertThatThrownBy(() -> orderService.createOrder(orderRequestDto, userEntity))
                .isInstanceOf(NoSuchDbRecordException.class)
                .hasMessageContaining("ID=999");

        Mockito.verify(eventRepository).existsById(999L);
        Mockito.verifyNoMoreInteractions(eventRepository);
        Mockito.verifyNoInteractions(seatRepository, orderRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    @DisplayName("Should reject Order without changing Seats when requested quantity is unavailable")
    void shouldThrowExceptionWhenNotEnoughSeatsAreAvailable(int availableSeats) {
        OrderRequestDto orderRequestDto = new OrderRequestDto(1L, 3);
        List<SeatEntity> seats = List.of(
                new SeatEntity(null, null, 400L, SeatStatus.AVAILABLE),
                new SeatEntity(null, null, 500L, SeatStatus.AVAILABLE)
        ).subList(0, availableSeats);

        Mockito.when(userRepository.findUserByEmail(userEntity.getEmail())).thenReturn(Optional.of(userEntity));
        Mockito.when(eventRepository.existsById(1L)).thenReturn(true);
        Mockito.when(seatRepository.findSeatByEventIdWithLocking(1L, SeatStatus.AVAILABLE,
                PageRequest.of(0, 3, Sort.by("seatId").ascending()))).thenReturn(seats);

        Assertions.assertThatThrownBy(() -> orderService.createOrder(orderRequestDto, userEntity))
                .isInstanceOf(NoSuchQuantityException.class)
                .hasMessageContaining("event ID=1")
                .hasMessageContaining("Expected 3 but available " + availableSeats);

        Assertions.assertThat(seats).allSatisfy(seat ->
                Assertions.assertThat(seat.getSeatStatus()).isEqualTo(SeatStatus.AVAILABLE));
        Mockito.verify(eventRepository).existsById(1L);
        Mockito.verifyNoMoreInteractions(eventRepository);
        Mockito.verifyNoInteractions(orderRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    @DisplayName("Should create Order with requested quantity of Seats from a single Event")
    void shouldSuccessfullyCreateOrder(int quantity) {
        Long eventId = 7L;
        OrderRequestDto orderRequestDto = new OrderRequestDto(eventId, quantity);
        EventEntity eventEntity = new EventEntity();
        List<SeatEntity> seats = List.of(
                new SeatEntity(eventEntity, null, 400L, SeatStatus.AVAILABLE),
                new SeatEntity(eventEntity, null, 500L, SeatStatus.AVAILABLE),
                new SeatEntity(eventEntity, null, 600L, SeatStatus.AVAILABLE)
        ).subList(0, quantity);

        Mockito.when(userRepository.findUserByEmail(userEntity.getEmail())).thenReturn(Optional.of(userEntity));
        Mockito.when(eventRepository.existsById(eventId)).thenReturn(true);
        Mockito.when(eventRepository.getReferenceById(eventId)).thenReturn(eventEntity);
        Mockito.when(seatRepository.findSeatByEventIdWithLocking(eventId, SeatStatus.AVAILABLE,
                PageRequest.of(0, quantity, Sort.by("seatId").ascending()))).thenReturn(seats);

        OrderResponseDto response = orderService.createOrder(orderRequestDto, userEntity);

        ArgumentCaptor<OrderEntity> orderEntityArgumentCaptor = ArgumentCaptor.forClass(OrderEntity.class);
        Mockito.verify(orderRepository).save(orderEntityArgumentCaptor.capture());
        Mockito.verify(seatRepository).findSeatByEventIdWithLocking(eventId, SeatStatus.AVAILABLE,
                PageRequest.of(0, quantity, Sort.by("seatId").ascending()));
        Mockito.verifyNoMoreInteractions(seatRepository);

        OrderEntity order = orderEntityArgumentCaptor.getValue();
        long fullPrice = seats.stream().mapToLong(SeatEntity::getPrice).sum();

        Assertions.assertThat(order.getTotalAmount()).isEqualTo(fullPrice);
        Assertions.assertThat(order.getPurchaser()).isSameAs(userEntity);
        Assertions.assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.NOT_INITIALIZED);
        Assertions.assertThat(order.getOrderItemList()).hasSize(quantity).allSatisfy(item -> {
            Assertions.assertThat(item.getEvent()).isSameAs(eventEntity);
            Assertions.assertThat(item.getOrder()).isSameAs(order);
            Assertions.assertThat(item.getPrice()).isEqualTo(item.getSeat().getPrice());
            Assertions.assertThat(item.getSeat().getSeatStatus()).isEqualTo(SeatStatus.LOCKED_FOR_CHECKOUT);
        });
        Assertions.assertThat(order.getOrderItemList()).extracting(OrderItemEntity::getSeat)
                .containsExactlyElementsOf(seats);
        Assertions.assertThat(response.fullPrice()).isEqualTo(fullPrice);
        Assertions.assertThat(response.purchaser()).isEqualTo(userEntity.getEmail());
        Assertions.assertThat(response.orderStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("Should retrieve existing PaymentIntent and revalidate Order when payment is pending")
    void shouldRetrieveExistingPaymentIntentAndRevalidateOrderWhenPaymentIsPending() throws StripeException, PaymentIntentStateMismatchException {
        // given
        UUID orderId = UUID.randomUUID();
        String paymentIntentId = "123";
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setPaymentStatus(PaymentStatus.PENDING);
        orderEntity.setStripePaymentIntentId(paymentIntentId);

        // when
        Mockito.when(orderTransactionService.validateOrderBefore(orderId, null)).thenReturn(orderEntity);

        Mockito.when(stripeClientMock.v1()).thenReturn(v1ServicesMock);
        Mockito.when(v1ServicesMock.paymentIntents()).thenReturn(paymentIntentServiceMock);
        PaymentIntent paymentIntent = new PaymentIntent();
        paymentIntent.setStatus("test-status");
        Mockito.when(paymentIntentServiceMock.retrieve(paymentIntentId)).thenReturn(paymentIntent);

        // then
        orderService.initializePayment(null, orderId);

        InOrder inOrder = Mockito.inOrder(stripeClientMock.v1().paymentIntents(), orderTransactionService);

        inOrder.verify(stripeClientMock.v1().paymentIntents(), Mockito.times(1)).retrieve(paymentIntentId);
        inOrder.verify(orderTransactionService, Mockito.times(1)).validateOrderAfterRetrieve(orderId);

        Mockito.verify(stripeClientMock.v1().paymentIntents(), Mockito.never()).create(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Should create PaymentIntent and update Order when payment is not initialized")
    void shouldCreatePaymentIntentWhenPaymentIsNotInitialized() throws StripeException, PaymentIntentStateMismatchException {
        // given
        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);
        orderEntity.setStripePaymentIntentId(null);
        ReflectionTestUtils.setField(orderEntity, "orderId", orderId);

        String idempotencyPrefix = "test-prefix";
        ReflectionTestUtils.setField(orderService, "idempotencyKeyPrefix", idempotencyPrefix);
        String validIdempotencyKey = idempotencyPrefix + orderId;

        String paymentIntentId = "12345";
        String paymentStatus = "test-status";
        String clientSecret = "test-secret";

        PaymentIntent paymentIntent = new PaymentIntent();
        paymentIntent.setStatus(paymentStatus);
        paymentIntent.setId(paymentIntentId);
        paymentIntent.setClientSecret(clientSecret);

        // when
        Mockito.when(orderTransactionService.validateOrderBefore(orderId, null)).thenReturn(orderEntity);

        Mockito.when(stripeClientMock.v1()).thenReturn(v1ServicesMock);
        Mockito.when(v1ServicesMock.paymentIntents()).thenReturn(paymentIntentServiceMock);
        Mockito.when(paymentIntentServiceMock.create(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(paymentIntent);

        // then
        OrderPaymentResponseDto orderPaymentResponseDto = orderService.initializePayment(null, orderId);

        ArgumentCaptor<PaymentIntentCreateParams> paymentIntentCreateParamsCaptor = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        ArgumentCaptor<RequestOptions> requestOptionsCaptor = ArgumentCaptor.forClass(RequestOptions.class);

        Mockito.verify(stripeClientMock.v1().paymentIntents(), Mockito.times(1))
                .create(paymentIntentCreateParamsCaptor.capture(), requestOptionsCaptor.capture());

        PaymentIntentCreateParams paymentIntentCreateParamsCaptured = paymentIntentCreateParamsCaptor.getValue();
        // paymentIntentCreateParamsCaptor
        Map<String, String> metadataMap = paymentIntentCreateParamsCaptured.getMetadata();
        Assertions.assertThat(metadataMap.containsKey("orderId")).isTrue();
        Assertions.assertThat(metadataMap.get("orderId")).isEqualTo(orderId.toString());

        // requestOptionsCaptor
        RequestOptions requestOptionsCaptured = requestOptionsCaptor.getValue();
        Assertions.assertThat(validIdempotencyKey).isEqualTo(requestOptionsCaptured.getIdempotencyKey());

        ArgumentCaptor<UUID> orderIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> paymentIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> initializedDateCaptor = ArgumentCaptor.forClass(Instant.class);
        Mockito.verify(orderTransactionService, Mockito.times(1))
                .tryUpdateOrderEntityAfterPaymentInitialization(orderIdCaptor.capture(), paymentIdCaptor.capture(), initializedDateCaptor.capture());
        Assertions.assertThat(orderId).isEqualTo(orderIdCaptor.getValue());
        Assertions.assertThat(paymentIntentId).isEqualTo(paymentIdCaptor.getValue());
        Assertions.assertThat(initializedDateCaptor.getValue()).isNotNull();

        Assertions.assertThat(paymentIntentId).isEqualTo(orderPaymentResponseDto.paymentId());
        Assertions.assertThat(paymentStatus).isEqualTo(orderPaymentResponseDto.providerPaymentStatus());
        Assertions.assertThat(clientSecret).isEqualTo(orderPaymentResponseDto.clientSecret());
        Mockito.verify(stripeClientMock.v1().paymentIntents(), Mockito.never()).retrieve(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Should fail payment initialization after exhausting idempotency conflict retries")
    void shouldThrowExceptionWhenIdempotencyConflictRetriesAreExhausted() throws StripeException, PaymentIntentStateMismatchException {
        // given
        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);
        orderEntity.setStripePaymentIntentId(null);
        ReflectionTestUtils.setField(orderEntity, "orderId", orderId);

        String stripeErrorCode = "idempotency_key_in_use";

        Mockito.when(orderTransactionService.validateOrderBefore(orderId, null)).thenReturn(orderEntity);

        Mockito.when(stripeClientMock.v1()).thenReturn(v1ServicesMock);
        Mockito.when(v1ServicesMock.paymentIntents()).thenReturn(paymentIntentServiceMock);
        StripeException stripeException = new IdempotencyException("testMessage", "test-request-id", stripeErrorCode, 0);

        Mockito.when(paymentIntentServiceMock.create(ArgumentMatchers.any(), ArgumentMatchers.any())).thenThrow(stripeException);

        // when
        Assertions.assertThatThrownBy(() -> orderService.initializePayment(null, orderId))
                .isInstanceOf(InitializationPaymentException.class)
                .hasMessageContaining("is currently processing by other process. Try again later");

        // then
        Mockito.verify(stripeClientMock.v1().paymentIntents(), Mockito.times(5)).create(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(orderTransactionService, Mockito.never()).tryUpdateOrderEntityAfterPaymentInitialization(ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Should successfully create PaymentIntent after temporary idempotency conflicts")
    void shouldSuccessfullyCreatePaymentIntentAfterIdempotencyConflictRetries() throws StripeException, PaymentIntentStateMismatchException {
        // given
        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);
        orderEntity.setStripePaymentIntentId(null);
        ReflectionTestUtils.setField(orderEntity, "orderId", orderId);

        String stripeErrorCode = "idempotency_key_in_use";

        String paymentIntentId = "12345";
        String paymentStatus = "test-status";
        String clientSecret = "test-secret";

        PaymentIntent paymentIntent = new PaymentIntent();
        paymentIntent.setStatus(paymentStatus);
        paymentIntent.setId(paymentIntentId);
        paymentIntent.setClientSecret(clientSecret);

        Mockito.when(orderTransactionService.validateOrderBefore(orderId, null)).thenReturn(orderEntity);

        Mockito.when(stripeClientMock.v1()).thenReturn(v1ServicesMock);
        Mockito.when(v1ServicesMock.paymentIntents()).thenReturn(paymentIntentServiceMock);

        StripeException idempotencyException = new IdempotencyException("test-message", "test-requestId", stripeErrorCode, 0);

        Mockito.when(paymentIntentServiceMock.create(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(idempotencyException)
                .thenThrow(idempotencyException)
                .thenReturn(paymentIntent);

        // when
        OrderPaymentResponseDto orderPaymentResponseDto = orderService.initializePayment(null, orderId);

        // then
        Mockito.verify(paymentIntentServiceMock, Mockito.times(3)).create(ArgumentMatchers.any(),ArgumentMatchers.any());
        Mockito.verify(paymentIntentServiceMock, Mockito.never()).retrieve(ArgumentMatchers.any());

        ArgumentCaptor<UUID> orderIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> paymentIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> initializedDateCaptor = ArgumentCaptor.forClass(Instant.class);
        Mockito.verify(orderTransactionService, Mockito.times(1))
                .tryUpdateOrderEntityAfterPaymentInitialization(orderIdCaptor.capture(), paymentIdCaptor.capture(), initializedDateCaptor.capture());
        Assertions.assertThat(orderId).isEqualTo(orderIdCaptor.getValue());
        Assertions.assertThat(paymentIntentId).isEqualTo(paymentIdCaptor.getValue());
        Assertions.assertThat(initializedDateCaptor.getValue()).isNotNull();

        Assertions.assertThat(paymentIntentId).isEqualTo(orderPaymentResponseDto.paymentId());
        Assertions.assertThat(paymentStatus).isEqualTo(orderPaymentResponseDto.providerPaymentStatus());
        Assertions.assertThat(clientSecret).isEqualTo(orderPaymentResponseDto.clientSecret());
    }

    @Test
    @DisplayName("Should translate Stripe exception and never update Order when PaymentIntent creation fails")
    void shouldTranslateStripeExceptionAndNeverUpdateOrder() throws StripeException, PaymentIntentStateMismatchException{
        // given
        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);
        orderEntity.setStripePaymentIntentId(null);
        ReflectionTestUtils.setField(orderEntity, "orderId", orderId);

        Mockito.when(orderTransactionService.validateOrderBefore(orderId, null)).thenReturn(orderEntity);

        Mockito.when(stripeClientMock.v1()).thenReturn(v1ServicesMock);
        Mockito.when(v1ServicesMock.paymentIntents()).thenReturn(paymentIntentServiceMock);
        Mockito.when(paymentIntentServiceMock.create(ArgumentMatchers.any(),ArgumentMatchers.any())).thenThrow(ApiConnectionException.class);

        // when
        Assertions.assertThatThrownBy(() ->orderService.initializePayment(null, orderId))
                .isInstanceOf(ExternalPaymentServiceException.class);

        // then
        Mockito.verify(orderTransactionService, Mockito.never()).tryUpdateOrderEntityAfterPaymentInitialization(ArgumentMatchers.any(),ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Should fail payment initialization when Order cannot be claimed for update")
    void shouldThrowExceptionWhenOrderCannotBeClaimedForUpdate() throws StripeException, PaymentIntentStateMismatchException{
        // given
        UUID orderId = UUID.randomUUID();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);
        orderEntity.setStripePaymentIntentId(null);
        ReflectionTestUtils.setField(orderEntity, "orderId", orderId);

        String paymentIntentId = "12345";
        String paymentStatus = "test-status";
        String clientSecret = "test-secret";

        PaymentIntent paymentIntent = new PaymentIntent();
        paymentIntent.setStatus(paymentStatus);
        paymentIntent.setId(paymentIntentId);
        paymentIntent.setClientSecret(clientSecret);

        Mockito.when(orderTransactionService.validateOrderBefore(orderId, null)).thenReturn(orderEntity);

        Mockito.when(stripeClientMock.v1()).thenReturn(v1ServicesMock);
        Mockito.when(v1ServicesMock.paymentIntents()).thenReturn(paymentIntentServiceMock);
        Mockito.when(paymentIntentServiceMock.create(ArgumentMatchers.any(),ArgumentMatchers.any())).thenReturn(paymentIntent);
        Mockito.doThrow(CannotAcquireLockException.class).when(orderTransactionService)
                .tryUpdateOrderEntityAfterPaymentInitialization(ArgumentMatchers.any(),ArgumentMatchers.anyString(),ArgumentMatchers.any());

        // when
        Assertions.assertThatThrownBy(() ->orderService.initializePayment(null, orderId))
                .isInstanceOf(InitializationPaymentException.class)
                .hasCauseInstanceOf(CannotAcquireLockException.class);

    }
}

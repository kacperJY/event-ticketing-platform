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
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;
import pl.kacper.sales_api.common.exception.NoSuchQuantityException;
import pl.kacper.sales_api.common.exception.paymentException.ExternalPaymentServiceException;
import pl.kacper.sales_api.common.exception.paymentException.InitializationPaymentException;
import pl.kacper.sales_api.common.exception.paymentException.PaymentIntentStateMismatchException;
import pl.kacper.sales_api.domain.event.EventRepository;
import pl.kacper.sales_api.domain.order.dto.OrderPaymentResponseDto;
import pl.kacper.sales_api.domain.order.dto.OrderRequestDto;
import pl.kacper.sales_api.domain.order.dto.TicketRequestDto;
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


    private List<TicketRequestDto> ticketRequestDtoList;
    private UserEntity userEntity;

    @BeforeEach()
    void initializeState() {
        userEntity = new UserEntity("mail@gmail.com", "password", "Firstname", "Lastname");
        ;
        ticketRequestDtoList = List.of(
                new TicketRequestDto(1L, 1),
                new TicketRequestDto(2L, 2),
                new TicketRequestDto(3L, 3)
        );
    }

    @Test
    @DisplayName("Should reject Order creation when provided Event IDs are invalid or duplicated")
    void shouldThrowExceptionWhenProvidedEventIdsDoNotMatchExistingEvents() {
        OrderRequestDto orderRequestDto = new OrderRequestDto(ticketRequestDtoList);

        long eventNumber = Math.max(0, ticketRequestDtoList.size() - 2);
        Mockito.when(eventRepository.countByEventIdIn(ArgumentMatchers.anyList())).thenReturn(eventNumber);

        Assertions.assertThatThrownBy(() -> orderService.createOrder(orderRequestDto, userEntity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Some of the event IDs are incorrect");

    }

    @Test
    @DisplayName("Should throw UsernameNotFoundException when Order owner does not exist")
    void shouldThrowExceptionWhenOrderOwnerDoesNotExist() {
        OrderRequestDto orderRequestDto = new OrderRequestDto(ticketRequestDtoList);

        Mockito.when(eventRepository.countByEventIdIn(ArgumentMatchers.anyList())).thenReturn((long) ticketRequestDtoList.size());

        Mockito.when(userRepository.findUserByEmail(userEntity.getEmail())).thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() -> orderService.createOrder(orderRequestDto, userEntity))
                .isInstanceOf(UsernameNotFoundException.class);
    }


    @Test
    @DisplayName("Should throw NoSuchQuantityException when there are not enough available Seats")
    void shouldThrowExceptionWhenNotEnoughSeatsAreAvailable() {
        OrderRequestDto orderRequestDto = new OrderRequestDto(ticketRequestDtoList);

        Mockito.when(eventRepository.countByEventIdIn(ArgumentMatchers.anyList())).thenReturn((long) ticketRequestDtoList.size());

        Mockito.when(userRepository.findUserByEmail(userEntity.getEmail())).thenReturn(Optional.of(userEntity));

        Mockito.when(seatRepository.findSeatByEventIdWithLocking(ArgumentMatchers.anyLong(), ArgumentMatchers.any(SeatStatus.class), ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());

        Assertions.assertThatThrownBy(() -> orderService.createOrder(orderRequestDto, userEntity))
                .isInstanceOf(NoSuchQuantityException.class);
    }

    @Test
    @DisplayName("Should successfully create a new Order and calculate its total amount")
    void shouldSuccessfullyCreateOrder() {
        OrderRequestDto orderRequestDto = new OrderRequestDto(ticketRequestDtoList);

        Mockito.when(eventRepository.countByEventIdIn(ArgumentMatchers.anyList())).thenReturn((long) ticketRequestDtoList.size());

        Mockito.when(userRepository.findUserByEmail(userEntity.getEmail())).thenReturn(Optional.of(userEntity));

        List<SeatEntity> seatEntityListFor1 = List.of(
                new SeatEntity(null, null, 400L, SeatStatus.AVAILABLE)
        );

        List<SeatEntity> seatEntityListFor2 = List.of(
                new SeatEntity(null, null, 500L, SeatStatus.AVAILABLE),
                new SeatEntity(null, null, 600L, SeatStatus.AVAILABLE)
        );

        List<SeatEntity> seatEntityListFor3 = List.of(
                new SeatEntity(null, null, 700L, SeatStatus.AVAILABLE),
                new SeatEntity(null, null, 800L, SeatStatus.AVAILABLE),
                new SeatEntity(null, null, 900L, SeatStatus.AVAILABLE)
        );

        Mockito.when(seatRepository.findSeatByEventIdWithLocking(ArgumentMatchers.eq(1L), ArgumentMatchers.eq(SeatStatus.AVAILABLE), ArgumentMatchers.any()))
                .thenReturn(seatEntityListFor1);

        Mockito.when(seatRepository.findSeatByEventIdWithLocking(ArgumentMatchers.eq(2L), ArgumentMatchers.eq(SeatStatus.AVAILABLE), ArgumentMatchers.any()))
                .thenReturn(seatEntityListFor2);

        Mockito.when(seatRepository.findSeatByEventIdWithLocking(ArgumentMatchers.eq(3L), ArgumentMatchers.eq(SeatStatus.AVAILABLE), ArgumentMatchers.any()))
                .thenReturn(seatEntityListFor3);

        orderService.createOrder(orderRequestDto, userEntity);

        ArgumentCaptor<OrderEntity> orderEntityArgumentCaptor = ArgumentCaptor.forClass(OrderEntity.class);
        Mockito.verify(orderRepository, Mockito.times(1)).save(orderEntityArgumentCaptor.capture());

        OrderEntity orderEntity = orderEntityArgumentCaptor.getValue();

        ArrayList<SeatEntity> allSeatEntities = new ArrayList<>();
        allSeatEntities.addAll(seatEntityListFor1);
        allSeatEntities.addAll(seatEntityListFor2);
        allSeatEntities.addAll(seatEntityListFor3);
        long fullPrice = allSeatEntities.stream().mapToLong(SeatEntity::getPrice).sum();

        Assertions.assertThat(orderEntity.getTotalAmount()).isEqualTo(fullPrice);
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

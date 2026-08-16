package pl.kacper.sales_api.domain.order;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.common.exception.NoSuchQuantityException;
import pl.kacper.sales_api.common.exception.paymentException.InitializationPaymentException;
import pl.kacper.sales_api.common.exception.paymentException.PaymentInconsistentStateException;
import pl.kacper.sales_api.common.exception.paymentException.PaymentIntentStateMismatchException;
import pl.kacper.sales_api.common.exception.paymentException.ExternalPaymentServiceException;
import pl.kacper.sales_api.domain.event.EventEntity;
import pl.kacper.sales_api.domain.event.EventRepository;
import pl.kacper.sales_api.domain.order.dto.OrderPaymentResponseDto;
import pl.kacper.sales_api.domain.order.dto.OrderRequestDto;
import pl.kacper.sales_api.domain.order.dto.OrderResponseDto;
import pl.kacper.sales_api.domain.order.dto.TicketRequestDto;
import pl.kacper.sales_api.domain.seat.SeatEntity;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;
import pl.kacper.sales_api.domain.user.UserEntity;
import pl.kacper.sales_api.domain.user.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class OrderService {

    private final static Logger LOGGER = LoggerFactory.getLogger(OrderService.class);

    private final SeatRepository seatRepository;
    private final OrderRepository orderRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final StripeClient stripeClient;
    private final OrderTransactionService orderTransactionService;

    @Value("${stripe.idempotency-key.prefix}")
    private String idempotencyKeyPrefix;

    @Value("${order.time-to-expired}")
    private int orderTimeToExpiredMinutes;

    private static final int MAX_RETRIES = 5;

    @Autowired
    public OrderService(SeatRepository seatRepository, OrderRepository orderRepository, EventRepository eventRepository,
                        UserRepository userRepository, StripeClient stripeClient, OrderTransactionService orderTransactionService) {
        this.seatRepository = seatRepository;
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.stripeClient = stripeClient;
        this.orderTransactionService = orderTransactionService;
    }

    @Transactional
    public OrderResponseDto createOrder(OrderRequestDto orderRequestDto, UserDetails userDetails) {
        List<TicketRequestDto> ticketsDto = orderRequestDto.tickets();

        List<TicketRequestDto> tickets = new ArrayList<>(ticketsDto);
        tickets.sort(Comparator.comparing(TicketRequestDto::eventId));

        List<Long> eventIdList = tickets.stream()
                .map(TicketRequestDto::eventId)
                .toList();

        long existedEvents = eventRepository.countByEventIdIn(eventIdList);

        // Safe-check if all event exists - to prevent fetching SeatEntity record for no reason
        if (existedEvents != eventIdList.size())
            throw new IllegalArgumentException("Some of the event IDs are incorrect or passed duplicate event ID. Provided %d, but found %d."
                    .formatted(eventIdList.size(), existedEvents));

        UserEntity userEntity = userRepository.findUserByEmail(userDetails.getUsername()).
                orElseThrow(() -> new UsernameNotFoundException("Invalid username. Cannot find user with such username: " + userDetails.getUsername()));


        Map<Long, List<SeatEntity>> seatEntitiesByEventId = mapAvailablePlacesWithEventEntities(tickets);

        long fullPrice = 0;
        OrderEntity orderEntity = new OrderEntity();
        for (Map.Entry<Long, List<SeatEntity>> entry : seatEntitiesByEventId.entrySet()) {
            Long eventId = entry.getKey();
            EventEntity eventEntityReference = eventRepository.getReferenceById(eventId);
            for (SeatEntity seatEntity : entry.getValue()) {
                seatEntity.setSeatStatus(SeatStatus.LOCKED_FOR_CHECKOUT); // DirtyChecking - not explicit seatRepository.save()
                fullPrice += seatEntity.getPrice();
                OrderItemEntity orderItemEntity = createOrderItemEntity(seatEntity.getPrice(), seatEntity, eventEntityReference);
                orderEntity.addOrderItem(orderItemEntity);
            }
        }
        orderEntity.setPurchaser(userEntity);
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPaymentStatus(PaymentStatus.NOT_INITIALIZED);
        orderEntity.setExpiresAt(Instant.now().plus(Duration.ofMinutes(orderTimeToExpiredMinutes)));
        orderEntity.setTotalAmount(fullPrice);

        orderRepository.save(orderEntity);

        return new OrderResponseDto(
                orderEntity.getOrderId(),
                orderEntity.getPurchaser().getEmail(),
                fullPrice,
                orderEntity.getCreatedAt(),
                orderEntity.getOrderStatus()
        );
    }

    private OrderItemEntity createOrderItemEntity(long price, SeatEntity seat, EventEntity eventEntity) {
        return new OrderItemEntity(price, seat, eventEntity);
    }


    private Map<Long, List<SeatEntity>> mapAvailablePlacesWithEventEntities(List<TicketRequestDto> tickets) {
        Map<Long, List<SeatEntity>> seatEntityListOfEventMap = new HashMap<>();

        for (TicketRequestDto ticket : tickets) {
            Long eventId = ticket.eventId();
            int quantity = ticket.quantity();
            PageRequest pageRequest = PageRequest.of(0, quantity, Sort.by("seatId").ascending());

            List<SeatEntity> seatEntityList = seatRepository.findSeatByEventIdWithLocking(eventId, SeatStatus.AVAILABLE, pageRequest);

            if (seatEntityList.size() != quantity)
                throw new NoSuchQuantityException("There are not enough available tickets for event ID=%d. Expected %d but available %d. Order will not be completed"
                        .formatted(eventId, quantity, seatEntityList.size()));

            seatEntityListOfEventMap.put(eventId, seatEntityList);

        }

        return seatEntityListOfEventMap;
    }


    public OrderPaymentResponseDto initializePayment(UserDetails userDetails, UUID orderId) {
        // BEFORE - CREATE REQUEST
        OrderEntity orderEntity;
        try {
            orderEntity = orderTransactionService.validateOrderBefore(orderId, userDetails); // TX
        } catch (PaymentIntentStateMismatchException e) {
            throw new PaymentInconsistentStateException("Order[orderId=%s] Payment could not be initialized due to an internal processing error.".formatted(orderId), e); // TEMPORARY SOLUTION
        }

        // IF PaymentIntent already exists - RETRIEVE
        if (orderEntity.getPaymentStatus() == PaymentStatus.PENDING && orderEntity.getStripePaymentIntentId() != null) {
            try {
                PaymentIntent retrieve = stripeClient.v1().paymentIntents().retrieve(orderEntity.getStripePaymentIntentId());
                String status = retrieve.getStatus();

                // TEMPORARY CHECKS
                switch (status) {
                    case "succeeded" ->
                            throw new InitializationPaymentException("Could not retrieve payment because it has already succeeded");
                    case "canceled" ->
                            throw new InitializationPaymentException("Could not retrieve payment because it has already canceled");
                }

                orderTransactionService.validateOrderAfterRetrieve(orderId); // TX

                return new OrderPaymentResponseDto(orderEntity.getStripePaymentIntentId(), retrieve.getClientSecret(), retrieve.getStatus());
            } catch (StripeException e) {
                LOGGER.error("""
                        Retrieving payment
                        Error code: {}
                        Status code: {}
                        Message: {}
                        """, e.getCode(), e.getStatusCode(), e.getMessage(), e);
                throw new ExternalPaymentServiceException("Could not retrieve payment with ID: " + orderEntity.getStripePaymentIntentId(), e);
            }
        }

        // STRIPE - CREATE REQUEST
        String idempotencyKey = idempotencyKeyPrefix + orderEntity.getOrderId(); // FOR the same Order always the same idempotency key
        PaymentIntent paymentIntent = null;

        int retriesCount = 0;
        while (retriesCount < MAX_RETRIES) {
            try {
                paymentIntent = createPaymentIntent(orderEntity, idempotencyKey);
                break;
            } catch (StripeException e) {
                if (e.getCode() != null && e.getCode().equals("idempotency_key_in_use")) {
                    if (++retriesCount == MAX_RETRIES) break;
                    try {
                        Thread.sleep(Duration.ofMillis(200));

                    } catch (InterruptedException threadException) {
                        LOGGER.error("""
                                ### ERROR: Request thread[threadName={}] for Order[orderID]={} has been interrupted
                                """, Thread.currentThread().getName(), orderId, threadException);
                        Thread.currentThread().interrupt();
                        throw new InitializationPaymentException("Payment initialization for Order [orderID]=%s couldn't be finalized. Try again later".formatted(orderId));
                    }
                    continue;
                }

                LOGGER.error("""
                        Initializing payment
                        Error code: {}
                        Status code: {}
                        Message: {}
                        """, e.getCode(), e.getStatusCode(), e.getMessage());
                throw new ExternalPaymentServiceException("Could not initialize payment", e); // FOR FUTURE DEVELOP
            }
        }
        // OUT of retries
        if (paymentIntent == null)
            throw new InitializationPaymentException("Order[orderID]=%s is currently processing by other process. Try again later".formatted(orderId));

        // AFTER - CREATE REQUEST
        String clientSecret = paymentIntent.getClientSecret();
        String status = paymentIntent.getStatus();
        String createdPaymentIntentId = paymentIntent.getId();

        try {
            // Try update OrderEntity
            orderTransactionService.tryUpdateOrderEntityAfterPaymentInitialization(orderId, createdPaymentIntentId, Instant.now()); // TX

        } catch (CannotAcquireLockException e) {
            LOGGER.debug(
                    "Could not update Order[orderId={}] after create payment-intent, because Order is claimed by other process",
                    orderId, e
            );
            throw new InitializationPaymentException("Order[orderId=%s] payment could not be initialized at this moment, because order is currently processed".formatted(orderId), e);
        } catch (PaymentIntentStateMismatchException ex) {
            throw new PaymentInconsistentStateException("Order[orderId=%s] Payment could not be initialized due to an internal processing error.".formatted(orderId), ex); // TEMPORARY SOLUTION
        }

        return new OrderPaymentResponseDto(createdPaymentIntentId, clientSecret, status);
    }

    private PaymentIntent createPaymentIntent(OrderEntity orderEntity, String idempotencyKey) throws StripeException {
        PaymentIntentCreateParams paymentIntentCreateParams =
                PaymentIntentCreateParams.builder()
                        .setAmount(orderEntity.getTotalAmount())
                        .setCurrency(orderEntity.getCurrencyType().getValue())
                        .putMetadata("orderId", orderEntity.getOrderId().toString())
                        .setAutomaticPaymentMethods(
                                PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                        .setEnabled(true)
                                        .build()
                        )
                        .build();
        RequestOptions requestOptions =
                RequestOptions.builder()
                        .setIdempotencyKey(idempotencyKey)
                        .build();

        return stripeClient.v1().paymentIntents().create(paymentIntentCreateParams, requestOptions);
    }
}

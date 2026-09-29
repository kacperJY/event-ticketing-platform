package pl.kacper.sales_api.domain.order.stripe;

import com.stripe.Stripe;
import com.stripe.net.Webhook;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class WebhookFlowIT extends BaseIT {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final UserRepository userRepository;
    private final StripeEventRepository stripeEventRepository;
    private final SeatRepository seatRepository;
    private final OrderItemRepository orderItemRepository;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    // State
    private UserEntity userEntity;
    private UUID orderId;
    private static final String STRIPE_PAYMENT_ID = "pi_123";
    private static final String STRIPE_EVENT_ID = "evt_test_123";
    private static final Long EVENT_ID = 1L;
    private static final int RESERVED_SEATS = 3;


    @Autowired
    public WebhookFlowIT(OrderRepository orderRepository, OrderService orderService, UserRepository userRepository, StripeEventRepository stripeEventRepository,
                         SeatRepository seatRepository, OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.userRepository = userRepository;
        this.stripeEventRepository = stripeEventRepository;
        this.seatRepository = seatRepository;
        this.orderItemRepository = orderItemRepository;
    }

    private void initializeState(OrderStatus orderStatus, PaymentStatus paymentStatus, String paymentIntentId, Instant initializedAt) {
        this.userEntity = new UserEntity("test@mail.com", "Testpass123", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        OrderRequestDto orderRequestDto = new OrderRequestDto(List.of(new TicketRequestDto(EVENT_ID, RESERVED_SEATS)));
        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);

        this.orderId = orderResponseDto.orderID();

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(orderId)));
        orderEntity.setPaymentStatus(paymentStatus);
        orderEntity.setOrderStatus(orderStatus);
        orderEntity.setPaymentInitializedAt(initializedAt);
        orderEntity.setStripePaymentIntentId(paymentIntentId);
        orderRepository.save(orderEntity);
    }

    private String createRawBody(String stripeEventId, String stripeEventType, String stripePaymentId, UUID orderId) {
        return """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "%s",
                  "type": "%s",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "payment_intent",
                      "metadata": {
                        "orderId": "%s"
                      }
                    }
                  }
                }
                """.formatted(
                stripeEventId,
                Stripe.API_VERSION,
                stripeEventType,
                stripePaymentId,
                orderId
        );
    }

    @Test
    @Sql(
            scripts = "classpath:/scripts/sql/init_event.sql"
    )
    public void shouldProcessSucceedWebhookAndCompleteOrder() throws Exception {
        initializeState(OrderStatus.PENDING, PaymentStatus.PENDING, STRIPE_PAYMENT_ID, Instant.now());

        String stripeEventType = StripeWebhookEvent.PI_SUCCEEDED.getValue();

        String rawBody = createRawBody(STRIPE_EVENT_ID, stripeEventType, STRIPE_PAYMENT_ID, this.orderId);

        String stripeSignature = Webhook.Signature.generateSignatureHeader(rawBody, webhookSecret);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", stripeSignature)
                .content(rawBody)
        ).andExpect(MockMvcResultMatchers.status().isOk());

        Assertions.assertThat(stripeEventRepository.existsById(STRIPE_EVENT_ID)).isTrue();

        StripeEventEntity stripeEventEntity = stripeEventRepository.findById(STRIPE_EVENT_ID)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no processed StripeEvent with orderId=%s in Test Database".formatted(STRIPE_EVENT_ID)));
        Assertions.assertThat(stripeEventEntity.getStripeEventId()).isEqualTo(STRIPE_EVENT_ID);
        Assertions.assertThat(stripeEventEntity.getOrderId()).isEqualTo(orderId);
        Assertions.assertThat(stripeEventEntity.getStripeEventType()).isEqualTo(stripeEventType);
        Assertions.assertThat(stripeEventEntity.getPaymentIntentId()).isEqualTo(STRIPE_PAYMENT_ID);
        Assertions.assertThat(stripeEventEntity.getProcessedAt()).isNotNull();

        OrderEntity orderEntityAfter = orderRepository.findById(this.orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(this.orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        Assertions.assertThat(orderEntityAfter.getPaidAt()).isNotNull();

        List<Long> seatIdsByOrderId = orderItemRepository.findSeatIdsByOrderIds(List.of(this.orderId));
        List<SeatEntity> seatsById = seatRepository.findAllById(seatIdsByOrderId);

        Assertions.assertThat(seatsById).hasSize(RESERVED_SEATS);
        Assertions.assertThat(seatsById).allMatch(seatEntity -> seatEntity.getSeatStatus() == SeatStatus.SOLD);
    }

    @Test
    @Sql(
            scripts = "classpath:/scripts/sql/init_event.sql"
    )
    public void shouldReturn4xxAndNotProcessWebhookForInvalidStripeSignature() throws Exception {
        initializeState(OrderStatus.PENDING, PaymentStatus.PENDING, STRIPE_PAYMENT_ID, Instant.now());

        String stripeEventType = StripeWebhookEvent.PI_SUCCEEDED.getValue();

        String rawBody = createRawBody(STRIPE_EVENT_ID, stripeEventType, STRIPE_PAYMENT_ID, this.orderId);

        String stripeSignature = Webhook.Signature.generateSignatureHeader(rawBody, webhookSecret);
        String invalidStripeSignature = stripeSignature + "##@@";

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", invalidStripeSignature)
                .content(rawBody)
        ).andExpect(MockMvcResultMatchers.status().isBadRequest());

        Assertions.assertThat(stripeEventRepository.existsById(STRIPE_EVENT_ID)).isFalse();

        OrderEntity orderEntityAfter = orderRepository.findById(this.orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(this.orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaidAt()).isNull();

        List<Long> seatIdsByOrderId = orderItemRepository.findSeatIdsByOrderIds(List.of(this.orderId));
        List<SeatEntity> seatsById = seatRepository.findAllById(seatIdsByOrderId);

        Assertions.assertThat(seatsById).hasSize(RESERVED_SEATS);
        Assertions.assertThat(seatsById).allMatch(seatEntity -> seatEntity.getSeatStatus() == SeatStatus.LOCKED_FOR_CHECKOUT);
    }

    @Test
    @Sql(
            scripts = "classpath:/scripts/sql/init_event.sql"
    )
    public void shouldReturn2xxAndNotProcessWebhookWhenNotSupportEventType() throws Exception {
        initializeState(OrderStatus.PENDING, PaymentStatus.PENDING, STRIPE_PAYMENT_ID, Instant.now());

        String stripeEventType = "custom_event_type";

        String rawBody = createRawBody(STRIPE_EVENT_ID, stripeEventType, STRIPE_PAYMENT_ID, this.orderId);

        String stripeSignature = Webhook.Signature.generateSignatureHeader(rawBody, webhookSecret);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", stripeSignature)
                .content(rawBody)
        ).andExpect(MockMvcResultMatchers.status().isOk());

        Assertions.assertThat(stripeEventRepository.existsById(STRIPE_EVENT_ID)).isFalse();

        OrderEntity orderEntityAfter = orderRepository.findById(this.orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(this.orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaidAt()).isNull();

        List<Long> seatIdsByOrderId = orderItemRepository.findSeatIdsByOrderIds(List.of(this.orderId));
        List<SeatEntity> seatsById = seatRepository.findAllById(seatIdsByOrderId);

        Assertions.assertThat(seatsById).hasSize(RESERVED_SEATS);
        Assertions.assertThat(seatsById).allMatch(seatEntity -> seatEntity.getSeatStatus() == SeatStatus.LOCKED_FOR_CHECKOUT);
    }

    @Test
    @Sql(
            scripts = "classpath:/scripts/sql/init_event.sql"
    )
    public void shouldReturn2xxAndIgnoreWebhookWhenOrderIdMetadataIsInvalid() throws Exception {
        initializeState(OrderStatus.PENDING, PaymentStatus.PENDING, STRIPE_PAYMENT_ID, Instant.now());

        String stripeEventType = StripeWebhookEvent.PI_SUCCEEDED.getValue();

        String rawBody = createRawBody(STRIPE_EVENT_ID, stripeEventType, STRIPE_PAYMENT_ID, null);

        String stripeSignature = Webhook.Signature.generateSignatureHeader(rawBody, webhookSecret);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", stripeSignature)
                .content(rawBody)
        ).andExpect(MockMvcResultMatchers.status().isOk());

        Assertions.assertThat(stripeEventRepository.existsById(STRIPE_EVENT_ID)).isFalse();

        OrderEntity orderEntityAfter = orderRepository.findById(this.orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(this.orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaidAt()).isNull();

        List<Long> seatIdsByOrderId = orderItemRepository.findSeatIdsByOrderIds(List.of(this.orderId));
        List<SeatEntity> seatsById = seatRepository.findAllById(seatIdsByOrderId);

        Assertions.assertThat(seatsById).hasSize(RESERVED_SEATS);
        Assertions.assertThat(seatsById).allMatch(seatEntity -> seatEntity.getSeatStatus() == SeatStatus.LOCKED_FOR_CHECKOUT);
    }


    @Test
    @Sql(
            scripts = "classpath:/scripts/sql/init_event.sql"
    )
    public void shouldReturn2xxAndNotProcessWebhookWhenInconsistentPaymentId() throws Exception {
        initializeState(OrderStatus.PENDING, PaymentStatus.PENDING, STRIPE_PAYMENT_ID, Instant.now());

        String stripeEventType = StripeWebhookEvent.PI_SUCCEEDED.getValue();

        String rawBody = createRawBody(STRIPE_EVENT_ID, stripeEventType, STRIPE_PAYMENT_ID + "9999", orderId);

        String stripeSignature = Webhook.Signature.generateSignatureHeader(rawBody, webhookSecret);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", stripeSignature)
                .content(rawBody)
        ).andExpect(MockMvcResultMatchers.status().isOk());

        Assertions.assertThat(stripeEventRepository.existsById(STRIPE_EVENT_ID)).isFalse();

        OrderEntity orderEntityAfter = orderRepository.findById(this.orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(this.orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaidAt()).isNull();

        List<Long> seatIdsByOrderId = orderItemRepository.findSeatIdsByOrderIds(List.of(this.orderId));
        List<SeatEntity> seatsById = seatRepository.findAllById(seatIdsByOrderId);

        Assertions.assertThat(seatsById).hasSize(RESERVED_SEATS);
        Assertions.assertThat(seatsById).allMatch(seatEntity -> seatEntity.getSeatStatus() == SeatStatus.LOCKED_FOR_CHECKOUT);
    }

    @Test
    @Sql(
            scripts = "classpath:/scripts/sql/init_event.sql"
    )
    public void shouldReturn5xxAndNotProcessWebhookWhenOrderPaymentIntentIsNull() throws Exception {
        initializeState(OrderStatus.PENDING, PaymentStatus.NOT_INITIALIZED, null, null);

        String stripeEventType = StripeWebhookEvent.PI_SUCCEEDED.getValue();

        String rawBody = createRawBody(STRIPE_EVENT_ID, stripeEventType, STRIPE_PAYMENT_ID, orderId);

        String stripeSignature = Webhook.Signature.generateSignatureHeader(rawBody, webhookSecret);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", stripeSignature)
                .content(rawBody)
        ).andExpect(MockMvcResultMatchers.status().isInternalServerError());

        Assertions.assertThat(stripeEventRepository.existsById(STRIPE_EVENT_ID)).isFalse();

        OrderEntity orderEntityAfter = orderRepository.findById(this.orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(this.orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.NOT_INITIALIZED);
        Assertions.assertThat(orderEntityAfter.getPaidAt()).isNull();

        List<Long> seatIdsByOrderId = orderItemRepository.findSeatIdsByOrderIds(List.of(this.orderId));
        List<SeatEntity> seatsById = seatRepository.findAllById(seatIdsByOrderId);

        Assertions.assertThat(seatsById).hasSize(RESERVED_SEATS);
        Assertions.assertThat(seatsById).allMatch(seatEntity -> seatEntity.getSeatStatus() == SeatStatus.LOCKED_FOR_CHECKOUT);
    }

    @Test
    @Sql(
            scripts = "classpath:/scripts/sql/init_event.sql"
    )
    public void shouldReturn2xxAndNotProcessWebhookWhenOrderNotExists() throws Exception {
        initializeState(OrderStatus.PENDING, PaymentStatus.PENDING, STRIPE_PAYMENT_ID, Instant.now());

        String stripeEventType = StripeWebhookEvent.PI_SUCCEEDED.getValue();

        String rawBody = createRawBody(STRIPE_EVENT_ID, stripeEventType, STRIPE_PAYMENT_ID, UUID.randomUUID());

        String stripeSignature = Webhook.Signature.generateSignatureHeader(rawBody, webhookSecret);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", stripeSignature)
                .content(rawBody)
        ).andExpect(MockMvcResultMatchers.status().isOk());

        Assertions.assertThat(stripeEventRepository.existsById(STRIPE_EVENT_ID)).isFalse();

        OrderEntity orderEntityAfter = orderRepository.findById(this.orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(this.orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaidAt()).isNull();

        List<Long> seatIdsByOrderId = orderItemRepository.findSeatIdsByOrderIds(List.of(this.orderId));
        List<SeatEntity> seatsById = seatRepository.findAllById(seatIdsByOrderId);

        Assertions.assertThat(seatsById).hasSize(RESERVED_SEATS);
        Assertions.assertThat(seatsById).allMatch(seatEntity -> seatEntity.getSeatStatus() == SeatStatus.LOCKED_FOR_CHECKOUT);
    }


    @Test
    @Sql(
            scripts = "classpath:/scripts/sql/init_event.sql"
    )
    public void shouldReturn2xxAndNotProcessWebhookWhenWebhookDoesNotContainOrderId() throws Exception {
        initializeState(OrderStatus.PENDING, PaymentStatus.PENDING, STRIPE_PAYMENT_ID, Instant.now());

        String stripeEventType = StripeWebhookEvent.PI_SUCCEEDED.getValue();

        String rawBody = """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "%s",
                  "type": "%s",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "payment_intent"
                    }
                  }
                }
                """.formatted(
                STRIPE_EVENT_ID,
                Stripe.API_VERSION,
                stripeEventType,
                STRIPE_PAYMENT_ID
        );

        String stripeSignature = Webhook.Signature.generateSignatureHeader(rawBody, webhookSecret);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", stripeSignature)
                .content(rawBody)
        ).andExpect(MockMvcResultMatchers.status().isOk());

        Assertions.assertThat(stripeEventRepository.existsById(STRIPE_EVENT_ID)).isFalse();

        OrderEntity orderEntityAfter = orderRepository.findById(this.orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(this.orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaidAt()).isNull();

        List<Long> seatIdsByOrderId = orderItemRepository.findSeatIdsByOrderIds(List.of(this.orderId));
        List<SeatEntity> seatsById = seatRepository.findAllById(seatIdsByOrderId);

        Assertions.assertThat(seatsById).hasSize(RESERVED_SEATS);
        Assertions.assertThat(seatsById).allMatch(seatEntity -> seatEntity.getSeatStatus() == SeatStatus.LOCKED_FOR_CHECKOUT);
    }

    @Test
    @Sql(
            scripts = "classpath:/scripts/sql/init_event.sql"
    )
    public void shouldReturn5xxAndNotProcessWebhookWhenCannotDeserializeContent() throws Exception {
        initializeState(OrderStatus.PENDING, PaymentStatus.PENDING, STRIPE_PAYMENT_ID, Instant.now());

        String stripeEventType = StripeWebhookEvent.PI_SUCCEEDED.getValue();

        String rawBody = """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "%s",
                  "type": "%s",
                  "created": "NO_DATA",
                  "data": {
                    "object": {
                      "id": "%s",
                      "metadata": {
                        "orderId": "%s"
                      }
                    }
                  }
                }
                """.formatted(
                STRIPE_EVENT_ID,
                Stripe.API_VERSION,
                stripeEventType,
                STRIPE_PAYMENT_ID,
                orderId
        );

        String stripeSignature = Webhook.Signature.generateSignatureHeader(rawBody, webhookSecret);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", stripeSignature)
                .content(rawBody)
        ).andExpect(MockMvcResultMatchers.status().isInternalServerError());

        Assertions.assertThat(stripeEventRepository.existsById(STRIPE_EVENT_ID)).isFalse();

        OrderEntity orderEntityAfter = orderRepository.findById(this.orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(this.orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaidAt()).isNull();

        List<Long> seatIdsByOrderId = orderItemRepository.findSeatIdsByOrderIds(List.of(this.orderId));
        List<SeatEntity> seatsById = seatRepository.findAllById(seatIdsByOrderId);

        Assertions.assertThat(seatsById).hasSize(RESERVED_SEATS);
        Assertions.assertThat(seatsById).allMatch(seatEntity -> seatEntity.getSeatStatus() == SeatStatus.LOCKED_FOR_CHECKOUT);
    }

    @Test
    @Sql(
            scripts = "classpath:/scripts/sql/init_event.sql"
    )
    public void shouldReturn5xxAndNotProcessWebhookWhenCannotDeserializeEventToStripeObject() throws Exception {
        initializeState(OrderStatus.PENDING, PaymentStatus.PENDING, STRIPE_PAYMENT_ID, Instant.now());

        String stripeEventType = StripeWebhookEvent.PI_SUCCEEDED.getValue();
        String stripeApiVersion = "2026-01-01.other";

        Assertions.assertThat(stripeApiVersion).isNotEqualTo(Stripe.API_VERSION);

        String rawBody = """
                {
                  "id": "%s",
                  "object": "event",
                  "api_version": "%s",
                  "type": "%s",
                  "data": {
                    "object": {
                      "id": "%s",
                      "metadata": {
                        "orderId": "%s"
                      }
                    }
                  }
                }
                """.formatted(
                STRIPE_EVENT_ID,
                stripeApiVersion,
                stripeEventType,
                STRIPE_PAYMENT_ID,
                orderId
        );

        String stripeSignature = Webhook.Signature.generateSignatureHeader(rawBody, webhookSecret);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", stripeSignature)
                .content(rawBody)
        ).andExpect(MockMvcResultMatchers.status().isInternalServerError());

        Assertions.assertThat(stripeEventRepository.existsById(STRIPE_EVENT_ID)).isFalse();

        OrderEntity orderEntityAfter = orderRepository.findById(this.orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("There is no Order with orderId=%s in Test Database".formatted(this.orderId)));
        Assertions.assertThat(orderEntityAfter.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        Assertions.assertThat(orderEntityAfter.getPaidAt()).isNull();

        List<Long> seatIdsByOrderId = orderItemRepository.findSeatIdsByOrderIds(List.of(this.orderId));
        List<SeatEntity> seatsById = seatRepository.findAllById(seatIdsByOrderId);

        Assertions.assertThat(seatsById).hasSize(RESERVED_SEATS);
        Assertions.assertThat(seatsById).allMatch(seatEntity -> seatEntity.getSeatStatus() == SeatStatus.LOCKED_FOR_CHECKOUT);
    }
}

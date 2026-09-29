package pl.kacper.sales_api.domain.order.stripe.refund;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.service.RefundService;
import com.stripe.service.V1Services;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.domain.BaseIT;
import pl.kacper.sales_api.domain.order.*;
import pl.kacper.sales_api.domain.order.dto.OrderRequestDto;
import pl.kacper.sales_api.domain.order.dto.OrderResponseDto;
import pl.kacper.sales_api.domain.order.dto.TicketRequestDto;
import pl.kacper.sales_api.domain.user.UserEntity;
import pl.kacper.sales_api.domain.user.UserRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
class RefundInitializerServiceIT extends BaseIT {

    private final RefundInitializerTransactionService refundInitializerTransactionService;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;

    private final String idempotency_key_prefix = "idempotency_prefix_";
    private final int batchSize = 50;

    @Mock
    private RefundService refundService;
    @Mock
    private V1Services v1Services;
    @Mock
    private StripeClient stripeClient;

    @Autowired
    RefundInitializerServiceIT(RefundInitializerTransactionService refundInitializerTransactionService, UserRepository userRepository, OrderRepository orderRepository, OrderService orderService) {
        this.refundInitializerTransactionService = refundInitializerTransactionService;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }

    @Test
    @Sql(scripts = {
            "classpath:scripts/sql/init_event.sql"
    })
    void shouldSuccessfullyInitRefund() throws StripeException {
        RefundInitializerService refundInitializerService = new RefundInitializerService(refundInitializerTransactionService, stripeClient);
        ReflectionTestUtils.setField(refundInitializerService, "batchSize", batchSize);
        ReflectionTestUtils.setField(refundInitializerService, "idempotencyKeyPrefix", idempotency_key_prefix);

        UserEntity userEntity = new UserEntity("test@gmail.com", "Password123", "TestFirstname", "TestLastname");
        userRepository.save(userEntity);

        final Long eventId = 1L;
        final int seatsReserved = 3;
        final String paymentIntentId = "pi_123";
        final String incomingRefundId = "re_333";
        OrderRequestDto orderRequestDto = new OrderRequestDto(List.of(new TicketRequestDto(eventId, seatsReserved)));
        OrderResponseDto orderResponseDto = orderService.createOrder(orderRequestDto, userEntity);
        UUID orderId = orderResponseDto.orderID();

        OrderEntity orderEntity = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Order with ID=%s does not exits".formatted(orderId)));
        orderEntity.setOrderStatus(OrderStatus.EXPIRED);
        orderEntity.setPaymentStatus(PaymentStatus.REFUND_REQUIRED);
        orderEntity.setStripePaymentIntentId(paymentIntentId);
        orderEntity.setPaidAt(Instant.now());

        orderRepository.save(orderEntity);

        Refund refund = new Refund();
        refund.setId(incomingRefundId);

        ArgumentCaptor<RefundCreateParams> refundCreateParamsArgumentCaptor = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> requestOptionsArgumentCaptor = ArgumentCaptor.forClass(RequestOptions.class);

        Mockito.when(stripeClient.v1()).thenReturn(v1Services);
        Mockito.when(v1Services.refunds()).thenReturn(refundService);
        Mockito.when(refundService.create(refundCreateParamsArgumentCaptor.capture(), requestOptionsArgumentCaptor.capture())).thenReturn(refund);

        refundInitializerService.scanAndInitRefunds();

        OrderEntity orderEntityAfter = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchDbRecordException("Order with ID=%s does not exits".formatted(orderId)));

        RefundCreateParams refundCreateParams = refundCreateParamsArgumentCaptor.getValue();
        RequestOptions requestOptions = requestOptionsArgumentCaptor.getValue();

        Assertions.assertThat(refundCreateParams.getAmount()).isEqualTo(orderEntityAfter.getTotalAmount());
        Assertions.assertThat(refundCreateParams.getPaymentIntent()).isEqualTo(orderEntityAfter.getStripePaymentIntentId());
        Assertions.assertThat(refundCreateParams.getCurrency()).isNull();
        Assertions.assertThat(requestOptions.getIdempotencyKey()).isEqualTo(idempotency_key_prefix + orderId);

        Map<?, ?> metadata = (Map<?, ?>) refundCreateParams.getMetadata();
        Assertions.assertThat(metadata.get("orderId")).isInstanceOf(String.class).asString().isEqualTo(orderId.toString());

        Assertions.assertThat(orderEntityAfter.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        Assertions.assertThat(orderEntityAfter.getStripeRefundId()).isEqualTo(incomingRefundId);
        Assertions.assertThat(orderEntityAfter.getRefundRequestedAt()).isNotNull();
        Assertions.assertThat(orderEntityAfter.getStripePaymentIntentId()).isEqualTo(paymentIntentId);
    }
}

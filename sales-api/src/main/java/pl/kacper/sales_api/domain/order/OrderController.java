package pl.kacper.sales_api.domain.order;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import pl.kacper.sales_api.domain.order.dto.OrderPaymentResponseDto;
import pl.kacper.sales_api.domain.order.dto.OrderRequestDto;
import pl.kacper.sales_api.domain.order.dto.OrderResponseDto;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private final OrderService orderService;

    @Autowired
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/order")
    public ResponseEntity<OrderResponseDto> createOrder(@RequestBody @Valid OrderRequestDto orderRequestDto, @AuthenticationPrincipal UserDetails userDetails){

        OrderResponseDto order = orderService.createOrder(orderRequestDto,userDetails);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @PostMapping("/order/{order_id}/payment-intent")
    public ResponseEntity<OrderPaymentResponseDto> initializePayment(@PathVariable("order_id") UUID orderId, @AuthenticationPrincipal UserDetails userDetails){

        OrderPaymentResponseDto orderPaymentResponseDto = orderService.initializePayment(userDetails, orderId);

        return ResponseEntity.status(HttpStatus.OK).body(orderPaymentResponseDto);
    }

}

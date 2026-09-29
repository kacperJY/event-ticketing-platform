package pl.kacper.sales_api.domain.order;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.domain.seat.SeatEntity;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderLifecycleTransactionService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SeatRepository seatRepository;

    @Autowired
    public OrderLifecycleTransactionService(OrderRepository orderRepository, OrderItemRepository orderItemRepository, SeatRepository seatRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int findAndUpdateExpiredOrders(Pageable pageable) {
        // Fetch with OrderItemList
        // Lock rows in table 'orders'
        List<OrderEntity> expiredOrders = orderRepository.findOrdersByOrderStatusAndExpiresAtWithLockingSkipLocked(Instant.now(), OrderStatus.PENDING, pageable);

        if (expiredOrders.isEmpty()) return 0;

        List<UUID> orderIdList = expiredOrders.stream()
                .map(OrderEntity::getOrderId)
                .toList();

        List<Long> seatIdsByOrderItemId = orderItemRepository.findSeatIdsByOrderIds(orderIdList);

        List<SeatEntity> seatEntityList = seatRepository.findAllById(seatIdsByOrderItemId);

        // ORDER
        for (OrderEntity order : expiredOrders)
            order.setOrderStatus(OrderStatus.EXPIRED);

        // SEATS
        for (SeatEntity seatEntity : seatEntityList)
            seatEntity.setSeatStatus(SeatStatus.AVAILABLE);

        return expiredOrders.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void expireOrderIfEligible(UUID orderId) {
        Optional<OrderEntity> expiredOrderEntityOptional = orderRepository.findSingleOrderByIdAndStatusAndExpiredAtWithLockingNoWait(orderId, Instant.now(), OrderStatus.PENDING);

        expiredOrderEntityOptional.ifPresent((expiredOrderEntity) -> {
            List<Long> seatIds = orderItemRepository.findSeatIdsByOrderIds(List.of(expiredOrderEntity.getOrderId()));

            List<SeatEntity> seatEntityList = seatRepository.findAllById(seatIds);

            expiredOrderEntity.setOrderStatus(OrderStatus.EXPIRED);

            for (SeatEntity seatEntity : seatEntityList)
                seatEntity.setSeatStatus(SeatStatus.AVAILABLE);
        });
    }

    private void cleanOrderSeatsWhen(UUID orderId, SeatStatus seatStatus) {

        List<Long> seatIds = orderItemRepository.findSeatIdsByOrderIds(List.of(orderId));

        List<SeatEntity> seatEntityList = seatRepository.findAllById(seatIds);

        for (SeatEntity seatEntity : seatEntityList)
            seatEntity.setSeatStatus(seatStatus);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void cleanOrderSeatsWhenCanceled(UUID orderId) {
        cleanOrderSeatsWhen(orderId, SeatStatus.AVAILABLE);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void cleanOrderSeatsWhenCompleted(UUID orderId) {
        cleanOrderSeatsWhen(orderId, SeatStatus.SOLD);
    }
}

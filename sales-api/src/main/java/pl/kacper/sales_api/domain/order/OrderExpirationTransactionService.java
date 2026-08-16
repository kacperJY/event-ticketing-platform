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
public class OrderExpirationTransactionService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SeatRepository seatRepository;

    @Autowired
    public OrderExpirationTransactionService(OrderRepository orderRepository, OrderItemRepository orderItemRepository, SeatRepository seatRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int findAndUpdateExpiredOrders(Pageable pageable) {
        // Fetch with OrderItemList
        // Lock rows in table 'orders'
        List<OrderEntity> expiredOrders = orderRepository.findOrderByOrderStatusAndExpiresAtWithLockingSkipLocked(Instant.now(), OrderStatus.PENDING, pageable);

        if (expiredOrders.isEmpty()) return 0;

        List<UUID> orderIdList = expiredOrders.stream()
                .map(OrderEntity::getOrderId)
                .toList();

        List<Long> seatIdsByOrderItemId = orderItemRepository.findSeatIdsByOrderId(orderIdList);

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
    public void expireOrderIfEligible(UUID orderId) {
        Optional<OrderEntity> expiredOrderEntityOptional = orderRepository.findOrderByIdAndStatusWithLockingNoWait(orderId, Instant.now(), OrderStatus.PENDING);

        expiredOrderEntityOptional.ifPresent((expiredOrderEntity) -> {
            List<Long> seatIds = orderItemRepository.findSeatIdsByOrderId(List.of(expiredOrderEntity.getOrderId()));

            List<SeatEntity> seatEntityList = seatRepository.findAllById(seatIds);

            expiredOrderEntity.setOrderStatus(OrderStatus.EXPIRED);

            for (SeatEntity seatEntity : seatEntityList)
                seatEntity.setSeatStatus(SeatStatus.AVAILABLE);
        });
    }

}

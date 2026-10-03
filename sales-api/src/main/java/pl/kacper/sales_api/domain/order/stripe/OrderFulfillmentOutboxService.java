package pl.kacper.sales_api.domain.order.stripe;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.domain.message.OutboxMessageEntity;
import pl.kacper.sales_api.domain.message.OutboxMessageRepository;
import pl.kacper.sales_api.domain.message.dto.message_payload.CompletedOrderMessagePayloadDto;
import pl.kacper.sales_api.domain.message.property.AggregateType;
import pl.kacper.sales_api.domain.message.property.MessagePayloadVersion;
import pl.kacper.sales_api.domain.message.property.OperationType;
import pl.kacper.sales_api.domain.order.OrderEntity;
import pl.kacper.sales_api.domain.order.OrderItemEntity;
import pl.kacper.sales_api.domain.order.OrderItemRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Service
class OrderFulfillmentOutboxService {

    private final OutboxMessageRepository outboxMessageRepository;
    private final ObjectMapper objectMapper;
    private final OrderItemRepository orderItemRepository;
    private final EntityManager entityManager;

    @Value("${rabbitmq.exchange.main-exchange}")
    private String mainExchangeName;
    @Value("${rabbitmq.sales-api.order-completed.routing-key}")
    private String completedOrderRoutingKey;

    public OrderFulfillmentOutboxService(OutboxMessageRepository outboxMessageRepository, ObjectMapper objectMapper,
                                         OrderItemRepository orderItemRepository, EntityManager entityManager) {
        this.outboxMessageRepository = outboxMessageRepository;
        this.objectMapper = objectMapper;
        this.orderItemRepository = orderItemRepository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createFulfillmentOutboxMessage(OrderEntity orderEntity) {
        Sort sort = Sort.by("orderItemId").ascending();
        List<OrderItemEntity> orderItemsWithEventAndSeatByOrderId = orderItemRepository.findOrderItemsWithEventAndSeatByOrderId(orderEntity.getOrderId(), sort);

        List<CompletedOrderMessagePayloadDto.Item> itemList = new ArrayList<>();
        for (OrderItemEntity orderItemEntity : orderItemsWithEventAndSeatByOrderId) {

            itemList.add(new CompletedOrderMessagePayloadDto.Item(
                    orderItemEntity.getOrderItemId(),
                    orderItemEntity.getEvent().getName(),
                    orderItemEntity.getEvent().getLocation().country(),
                    orderItemEntity.getEvent().getLocation().city(),
                    orderItemEntity.getEvent().getLocation().street(),
                    orderItemEntity.getEvent().getLocation().no(),
                    orderItemEntity.getEvent().getLocation().postalCode(),
                    orderItemEntity.getEvent().getEventDate(),
                    orderItemEntity.getEvent().getEventCategory(),
                    orderItemEntity.getSeat().getSeatNumber()
            ));
        }

        String email = entityManager.createQuery("select orderEntity.purchaser.email from OrderEntity orderEntity where orderEntity.orderId=:orderId", String.class)
                .setParameter("orderId", orderEntity.getOrderId())
                .getSingleResult();


        CompletedOrderMessagePayloadDto completedOrderMessagePayloadDto = new CompletedOrderMessagePayloadDto(
                orderEntity.getOrderId(),
                email,
                orderEntity.getPaidAt(),
                itemList
        );
        String jsonPayload = objectMapper.writeValueAsString(completedOrderMessagePayloadDto);

        OutboxMessageEntity outboxMessageEntity = new OutboxMessageEntity(
                jsonPayload,
                MessagePayloadVersion.V1,
                OperationType.PAID,
                AggregateType.ORDER,
                orderEntity.getOrderId().toString(),
                mainExchangeName,
                completedOrderRoutingKey
        );

        outboxMessageRepository.save(outboxMessageEntity);
    }
}

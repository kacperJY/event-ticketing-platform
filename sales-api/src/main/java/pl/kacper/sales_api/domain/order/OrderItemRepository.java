package pl.kacper.sales_api.domain.order;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderItemRepository extends ListCrudRepository<OrderItemEntity, Long> {

    @Query("select oie.seat.seatId FROM OrderItemEntity oie WHERE oie.order.orderId IN (:orderIdList)")
    List<Long> findSeatIdsByOrderIds(@Param("orderIdList") List<UUID> orderIdList);


    @Query("select orderItem from OrderItemEntity orderItem left join fetch orderItem.seat left join fetch orderItem.event where orderItem.order.orderId=:orderId")
    List<OrderItemEntity> findOrderItemsWithEventAndSeatByOrderId(@Param("orderId") UUID orderId, Sort sort);

}

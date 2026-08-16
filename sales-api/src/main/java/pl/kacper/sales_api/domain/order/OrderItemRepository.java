package pl.kacper.sales_api.domain.order;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderItemRepository extends ListCrudRepository<OrderItemEntity, Long> {

    @Query("select oie.seat.seatId FROM OrderItemEntity oie WHERE oie.order.orderId IN (:orderIdList)")
    List<Long> findSeatIdsByOrderId(@Param("orderIdList") List<UUID> orderIdList);
}

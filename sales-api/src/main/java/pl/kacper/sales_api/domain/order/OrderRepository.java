package pl.kacper.sales_api.domain.order;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OrderRepository extends ListCrudRepository<OrderEntity, UUID> {
}

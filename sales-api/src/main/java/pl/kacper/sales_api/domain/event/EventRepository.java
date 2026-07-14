package pl.kacper.sales_api.domain.event;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EventRepository extends ListCrudRepository<EventEntity,Long> {
}

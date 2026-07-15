package pl.kacper.sales_api.domain.eventTicket;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TicketRepository extends ListCrudRepository<TicketEntity, UUID> {
}

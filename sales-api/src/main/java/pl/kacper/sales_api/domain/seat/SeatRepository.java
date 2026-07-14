package pl.kacper.sales_api.domain.seat;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeatRepository extends ListCrudRepository<SeatEntity, Long> {

    int countByEvent_EventIdAndSeatStatus(Long eventEventId, SeatStatus seatStatus);
}

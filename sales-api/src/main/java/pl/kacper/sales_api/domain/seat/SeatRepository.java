package pl.kacper.sales_api.domain.seat;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SeatRepository extends ListCrudRepository<SeatEntity, Long> {

    int countByEvent_EventIdAndSeatStatus(Long eventEventId, SeatStatus seatStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("select seat from SeatEntity seat WHERE seat.event.eventId=:eventId AND seat.seatStatus=:seatStatus")
    List<SeatEntity> findSeatByEventIdWithLocking(@Param("eventId") Long eventId, @Param("seatStatus") SeatStatus seatStatus, Pageable pageable);

}

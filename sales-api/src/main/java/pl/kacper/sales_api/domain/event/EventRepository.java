package pl.kacper.sales_api.domain.event;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.kacper.sales_api.domain.event.dto.SimpleEventDto;

import java.util.Optional;

@Repository
public interface EventRepository extends ListCrudRepository<EventEntity,Long> {


    @Query("""
                select new pl.kacper.sales_api.domain.event.dto.SimpleEventDto(e.eventId, e.name, e.location.city) from EventEntity e
                WHERE (:city is not null AND e.location.city=:city) OR :city is null
                order by e.eventDate asc
           """)
    Page<SimpleEventDto> findEventEntitiesByCity(@Param("city") String city, Pageable pageable);

}

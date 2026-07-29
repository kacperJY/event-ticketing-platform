package pl.kacper.sales_api.domain.seat;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.domain.event.EventEntity;
import pl.kacper.sales_api.domain.message.dto.event.CreateEventMessagePayloadDto;


@Service
public class SeatService {
    private final static Logger LOGGER = LoggerFactory.getLogger(SeatService.class);

    @Value("${database.batch-size}")
    private int batchSize;

    private final EntityManager entityManager;

    @Autowired
    public SeatService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void createSeatsByEvent(CreateEventMessagePayloadDto createEventMessagePayloadDto) {
        int numberOfSeats = createEventMessagePayloadDto.placesNumber();

        for (int i = 1; i <= numberOfSeats; i++) {
            EventEntity referenceEvent = entityManager.getReference(EventEntity.class, createEventMessagePayloadDto.eventId());
            String seatNumber = createEventMessagePayloadDto.seatPrefix() + " - " + i;

            SeatEntity seatEntity = new SeatEntity(
                    referenceEvent,
                    seatNumber,
                    createEventMessagePayloadDto.pricePerSeat(),
                    SeatStatus.AVAILABLE
            );
            entityManager.persist(seatEntity);

            if (i % batchSize == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }
        entityManager.flush();
        entityManager.clear();

        LOGGER.info("Created {} seats for event id: {}", numberOfSeats, createEventMessagePayloadDto.eventId());
    }

}

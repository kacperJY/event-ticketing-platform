package pl.kacper.sales_api.domain.seat;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.domain.event.EventEntity;
import pl.kacper.sales_api.domain.event.dto.CreateEventMessageDto;


@RabbitListener(queues = "${rabbitmq.sales-api.queue-name.create-event}")
@Service
public class SeatService {

    private final static Logger LOGGER = LoggerFactory.getLogger(SeatService.class);

    private final EntityManager entityManager;

    @Value("${database.batch-size}")
    private int batchSize;

    @Autowired
    public SeatService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @RabbitHandler
    @Transactional
    public void createSeatsByEvent(CreateEventMessageDto createEventMessageDto) {
        LOGGER.info("Fetching message: {}", createEventMessageDto);

        int numberOfSeats = createEventMessageDto.placesNumber();

        for (int i = 1; i <= numberOfSeats; i++) {
            EventEntity referenceEvent = entityManager.getReference(EventEntity.class, createEventMessageDto.eventId());
            String seatNumber = createEventMessageDto.seatPrefix() + " - " + i;

            SeatEntity seatEntity = new SeatEntity(
                    referenceEvent,
                    seatNumber,
                    createEventMessageDto.pricePerSeat(),
                    SeatStatus.AVAILABLE
            );
            entityManager.persist(seatEntity);

            if (i % batchSize == 0){
                entityManager.flush();
                entityManager.clear();
            }
        }

        entityManager.flush();
        entityManager.clear();

        LOGGER.info("Created {} seats for event id: {}", numberOfSeats, createEventMessageDto.eventId());
    }

}

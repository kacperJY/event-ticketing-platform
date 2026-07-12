package pl.kacper.sales_api.domain.event;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import pl.kacper.sales_api.common.utils.PriceValueCalculator;
import pl.kacper.sales_api.domain.event.dto.CreateEventMessageDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventRequestDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventResponseDto;

import java.math.BigInteger;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.sales-api.routing-key.create-event}")
    private String createEventRoutingKey;

    @Value("${rabbitmq.exchange-name.exchange}")
    private String exchangeName;

    @Autowired
    public EventService(EventRepository eventRepository, RabbitTemplate rabbitTemplate) {
        this.eventRepository = eventRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public CreateEventResponseDto createEvent(CreateEventRequestDto createEventRequestDto){
        EventEntity eventEntity = new EventEntity(
                createEventRequestDto.name(),
                createEventRequestDto.description(),
                createEventRequestDto.eventCategory(),
                createEventRequestDto.location(),
                createEventRequestDto.eventDate(),
                createEventRequestDto.placesNumber()
        );

        eventRepository.save(eventEntity);

        CreateEventMessageDto createEventMessageDto = new CreateEventMessageDto(
                eventEntity.getEventId(),
                PriceValueCalculator.calculateZlotyToPennies(createEventRequestDto.seatPrice())
        );

        rabbitTemplate.convertAndSend(exchangeName,createEventRoutingKey, createEventMessageDto);

        return new CreateEventResponseDto(eventEntity.getEventId());
    }
}

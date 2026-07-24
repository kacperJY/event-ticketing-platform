package pl.kacper.sales_api.domain.event;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.domain.event.dto.CreateEventRequestDto;
import pl.kacper.sales_api.domain.message.OutboxMessageEntity;
import pl.kacper.sales_api.domain.message.OutboxMessageRepository;
import pl.kacper.sales_api.domain.message.dto.event.CreateEventMessagePayloadDto;
import pl.kacper.sales_api.domain.message.property.AggregateType;
import pl.kacper.sales_api.domain.message.property.MessagePayloadVersion;
import pl.kacper.sales_api.domain.message.property.OperationType;
import tools.jackson.databind.ObjectMapper;

@Service
public class EventTransactionService {

    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final OutboxMessageRepository outboxMessageRepository;

    @Value("${rabbitmq.exchange-name.exchange}")
    private String exchange;

    @Value("${rabbitmq.sales-api.routing-key.create-event}")
    private String routingKey;

    @Autowired
    public EventTransactionService(EventRepository eventRepository, ObjectMapper objectMapper, OutboxMessageRepository outboxMessageRepository) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.outboxMessageRepository = outboxMessageRepository;
    }

    @Transactional
    EventEntity saveEvent(CreateEventRequestDto createEventRequestDto){
        EventEntity eventEntity = new EventEntity(
                createEventRequestDto.name(),
                createEventRequestDto.description(),
                createEventRequestDto.eventCategory(),
                createEventRequestDto.location(),
                createEventRequestDto.eventDate(),
                createEventRequestDto.placesNumber()
        );

        eventRepository.save(eventEntity);

        CreateEventMessagePayloadDto createEventMessagePayloadDto = new CreateEventMessagePayloadDto(
                eventEntity.getEventId(),
                createEventRequestDto.seatPrice(),
                createEventRequestDto.placesNumber(),
                createEventRequestDto.name()
        );
        String payloadJson = objectMapper.writeValueAsString(createEventMessagePayloadDto);
        OutboxMessageEntity outboxMessageEntity = new OutboxMessageEntity(
                payloadJson,
                MessagePayloadVersion.V1,
                OperationType.CREATE,
                AggregateType.EVENT,
                String.valueOf(eventEntity.getEventId()),
                exchange,
                routingKey
                );
        outboxMessageRepository.save(outboxMessageEntity);

        return eventEntity;
    }
}

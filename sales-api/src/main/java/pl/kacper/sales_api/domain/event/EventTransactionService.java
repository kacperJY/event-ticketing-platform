package pl.kacper.sales_api.domain.event;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.common.exception.DuplicateDbRecordException;
import pl.kacper.sales_api.common.utils.PriceValueCalculator;
import pl.kacper.sales_api.domain.event.dto.CreateEventRequestDto;
import pl.kacper.sales_api.domain.message.OutboxMessageEntity;
import pl.kacper.sales_api.domain.message.OutboxMessageRepository;
import pl.kacper.sales_api.domain.message.dto.event.CreateEventMessagePayloadDto;
import pl.kacper.sales_api.domain.message.dto.EntityAndMessageDto;
import pl.kacper.sales_api.domain.message.property.AggregateType;
import pl.kacper.sales_api.domain.message.property.MessagePayloadVersion;
import pl.kacper.sales_api.domain.message.property.OperationType;
import tools.jackson.databind.ObjectMapper;

@Service
public class EventTransactionService {

    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final OutboxMessageRepository outboxMessageRepository;

    @Value("${rabbitmq.exchange.main-exchange}")
    private String mainExchange;

    @Value("${rabbitmq.sales-api.create-event.routing-key}")
    private String routingKey;

    @Autowired
    public EventTransactionService(EventRepository eventRepository, ObjectMapper objectMapper, OutboxMessageRepository outboxMessageRepository) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.outboxMessageRepository = outboxMessageRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    EntityAndMessageDto<Long> saveEventAndMessage(CreateEventRequestDto createEventRequestDto) {

        if (eventRepository.existsByName(createEventRequestDto.name()))
            throw new DuplicateDbRecordException("Event with name=%s is already exists, cannot add duplicate".formatted(createEventRequestDto.name()));

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
                PriceValueCalculator.calculateZlotyToPennies(createEventRequestDto.seatPrice()),
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
                mainExchange,
                routingKey
        );
        outboxMessageRepository.save(outboxMessageEntity);

        return new EntityAndMessageDto<Long>(eventEntity.getEventId(), outboxMessageEntity.getMessageId());
    }
}

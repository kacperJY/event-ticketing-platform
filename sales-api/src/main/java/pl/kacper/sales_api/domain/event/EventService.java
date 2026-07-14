package pl.kacper.sales_api.domain.event;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.common.utils.PriceValueCalculator;
import pl.kacper.sales_api.domain.dto.ElementsPageDto;
import pl.kacper.sales_api.domain.event.dto.*;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.sales-api.routing-key.create-event}")
    private String createEventRoutingKey;

    @Value("${rabbitmq.exchange-name.exchange}")
    private String exchangeName;

    private static final int PAGE_SIZE = 10;

    @Autowired
    public EventService(EventRepository eventRepository, SeatRepository seatRepository, RabbitTemplate rabbitTemplate) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CreateEventResponseDto createEvent(CreateEventRequestDto createEventRequestDto) {
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
                PriceValueCalculator.calculateZlotyToPennies(createEventRequestDto.seatPrice()),
                createEventRequestDto.placesNumber(),
                createEventRequestDto.name()
        );

        rabbitTemplate.convertAndSend(exchangeName, createEventRoutingKey, createEventMessageDto);

        return new CreateEventResponseDto(eventEntity.getEventId());
    }

    public ElementsPageDto<SimpleEventDto> getEvents(String city, int page) {

        if (page < 1)
            throw new IllegalArgumentException("Typed page argument is invalid. Cannot pass page number lower than 1");

        int normalizedPageNumber = page - 1; // start paging from 0

        PageRequest pageableRequest = PageRequest.of(normalizedPageNumber, PAGE_SIZE);

        Page<SimpleEventDto> resultPage = eventRepository.findEventEntitiesByCity(city, pageableRequest);

        int totalPages = resultPage.getTotalPages();
        long totalElements = resultPage.getTotalElements();
        int numberOfElements = resultPage.getNumberOfElements();

        if (page > totalPages)
            throw new IllegalArgumentException("Typed out of range page number: %d / %d ".formatted(page, totalPages));


        List<SimpleEventDto> content = resultPage.getContent();

        return new ElementsPageDto<SimpleEventDto>(
                page,
                totalPages,
                numberOfElements,
                PAGE_SIZE,
                totalElements,
                content
        );
    }

    public DetailEventDto getEventDetails(Long eventId){
        EventEntity eventEntity = eventRepository.findById(eventId).
                orElseThrow(() -> new NoSuchDbRecordException("Cannot find event record by passed ID. Probably passed invalid ID"));

        int counter = seatRepository.countByEvent_EventIdAndSeatStatus(eventId, SeatStatus.AVAILABLE);

        return new DetailEventDto(
                eventEntity.getEventId(),
                eventEntity.getName(),
                eventEntity.getDescription(),
                eventEntity.getEventCategory(),
                eventEntity.getLocation(),
                eventEntity.getEventDate(),
                eventEntity.getPlacesNumber(),
                counter
        );
    }
}

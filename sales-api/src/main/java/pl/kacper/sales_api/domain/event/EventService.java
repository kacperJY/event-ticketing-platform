package pl.kacper.sales_api.domain.event;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import pl.kacper.sales_api.common.utils.PriceValueCalculator;
import pl.kacper.sales_api.domain.dto.ElementsPageDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventMessageDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventRequestDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventResponseDto;
import pl.kacper.sales_api.domain.event.dto.SimpleEventDto;

import java.util.List;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.sales-api.routing-key.create-event}")
    private String createEventRoutingKey;

    @Value("${rabbitmq.exchange-name.exchange}")
    private String exchangeName;

    private static final int PAGE_SIZE = 10;

    @Autowired
    public EventService(EventRepository eventRepository, RabbitTemplate rabbitTemplate) {
        this.eventRepository = eventRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @PreAuthorize("hasRole('ADMIN')")
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

        if (totalElements > 0 && numberOfElements == 0)
            throw new IllegalArgumentException("Typed out of range page number: %d / %d ".formatted(page, totalPages));


        List<SimpleEventDto> content = resultPage.getContent();

        return new ElementsPageDto<SimpleEventDto>(
                page,
                numberOfElements,
                PAGE_SIZE,
                totalElements,
                content
        );
    }
}

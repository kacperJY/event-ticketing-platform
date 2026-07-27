package pl.kacper.sales_api.domain.event;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.domain.dto.ElementsPageDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventRequestDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventResponseDto;
import pl.kacper.sales_api.domain.event.dto.DetailEventDto;
import pl.kacper.sales_api.domain.event.dto.SimpleEventDto;
import pl.kacper.sales_api.domain.message.MessagePublisher;
import pl.kacper.sales_api.domain.message.dto.EntityAndMessageDto;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final EventTransactionService eventTransactionService;
    private final MessagePublisher messagePublisher;

    private static final int PAGE_SIZE = 10;

    public EventService(EventRepository eventRepository, SeatRepository seatRepository, EventTransactionService eventTransactionService, MessagePublisher messagePublisher) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.eventTransactionService = eventTransactionService;
        this.messagePublisher = messagePublisher;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public CreateEventResponseDto createEvent(CreateEventRequestDto createEventRequestDto) {

        // Transaction separated
        EntityAndMessageDto<Long> entityAndMessageDto = eventTransactionService.saveEventAndMessage(createEventRequestDto);

        // INSTANT-SEND after create
        messagePublisher.trySendSingleMessageAsync(entityAndMessageDto.messageID());

        return new CreateEventResponseDto(entityAndMessageDto.entityId());
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

    public DetailEventDto getEventDetails(Long eventId) {
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

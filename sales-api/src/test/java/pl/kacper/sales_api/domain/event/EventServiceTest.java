package pl.kacper.sales_api.domain.event;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.domain.dto.ElementsPageDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventMessageDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventRequestDto;
import pl.kacper.sales_api.domain.event.dto.DetailEventDto;
import pl.kacper.sales_api.domain.event.dto.SimpleEventDto;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
public class EventServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private SeatRepository seatRepository;

    @InjectMocks
    private EventService eventService;

    @Test
    @DisplayName("Should createEvent() CreateEventMessageDto contains exactly the same number of places to generate seats that event defined")
    void shouldMessageDtoContainsExactlyTheSameNumberOfPlacesThatEventDefined() {

        CreateEventRequestDto createEventRequestDto = new CreateEventRequestDto(
                null,
                null,
                null,
                null,
                0L,
                null,
                40
        );

        ArgumentCaptor<CreateEventMessageDto> captor = ArgumentCaptor.forClass(CreateEventMessageDto.class);

        eventService.createEvent(createEventRequestDto);

        Mockito.verify(rabbitTemplate).convertAndSend(ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), captor.capture());

        CreateEventMessageDto messageDto = captor.getValue();

        assertThat(messageDto.placesNumber()).isEqualTo(40);
    }

    @Test
    @DisplayName("Should getEvents() throw IllegalArgumentException when passed page number lower than 1")
    void shouldThrowExceptionWhenPassedPageNumberLowerThan1() {
        int pageNumber = 0;
        Assertions.assertThatThrownBy(() -> eventService.getEvents(null, pageNumber))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page number lower than 1");
    }

    @Test
    @DisplayName("Should getEvents() throw IllegalArgumentException when passed page number of out max range")
    void shouldThrowExceptionWhenPageNumberIsOutOfRange() {
        String city = null;

        final int pageSize = 10;

        int pageNumber = 4;
        int totalPages = 3;

        int pageNumberNormalized = pageNumber - 1;
        PageRequest pageRequest = PageRequest.of(pageNumberNormalized, pageSize);
        Page<SimpleEventDto> pageMock = Mockito.mock(Page.class);

        Mockito.when(pageMock.getTotalPages()).thenReturn(totalPages);
        Mockito.when(eventRepository.findEventEntitiesByCity(city, pageRequest)).thenReturn(pageMock);

        Assertions.assertThatThrownBy(() -> eventService.getEvents(city, pageNumber))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    @DisplayName("Should getEvents() returns result for correct page number")
    void shouldReturnResult() {
        String city = null;

        final int pageSize = 10;

        int pageNumber = 1;
        int totalPages = 1;

        int pageNumberNormalized = pageNumber - 1;
        PageRequest pageRequest = PageRequest.of(pageNumberNormalized, pageSize);
        Page<SimpleEventDto> pageMock = Mockito.mock(Page.class);

        Mockito.when(pageMock.getTotalPages()).thenReturn(totalPages);
        Mockito.when(eventRepository.findEventEntitiesByCity(city, pageRequest)).thenReturn(pageMock);
        Mockito.when(pageMock.getContent()).thenReturn(List.of(
                new SimpleEventDto(1L, null, null),
                new SimpleEventDto(2L, null, null)
        ));

        ElementsPageDto<SimpleEventDto> elementsPageDto = eventService.getEvents(city, pageNumber);

        assertThat(elementsPageDto.elements()).isNotEmpty();
    }

    @Test
    @DisplayName("Should getEventDetails() throws NoSuchDbRecordException when passed invalid ID of EventEntity")
    void  shouldThrowNoSuchDbRecordExceptionWhenPassedInvalidEventId(){
        Long invalidEventId = 99L;

        Mockito.when(eventRepository.findById(invalidEventId)).thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() -> eventService.getEventDetails(invalidEventId))
                .isInstanceOf(NoSuchDbRecordException.class);
    }

    @Test
    @DisplayName("Should getEventDetails() return result of DetailsEventDto when passed correct ID of EventEntity")
    void  shouldReturnResultWhenPassedCorrectEventId(){
        Long validEventId = 99L;
        int numbersOfAvailableSeats = 10;

        EventEntity eventEntity = new EventEntity(null, null, null, null, null, 0);
        ReflectionTestUtils.setField(eventEntity,"eventId",validEventId);

        Mockito.when(eventRepository.findById(validEventId)).thenReturn(Optional.of(eventEntity));
        Mockito.when(seatRepository.countByEvent_EventIdAndSeatStatus(validEventId, SeatStatus.AVAILABLE)).thenReturn(numbersOfAvailableSeats);

        DetailEventDto eventDetails = eventService.getEventDetails(validEventId);

        assertThat(eventDetails.eventId()).isEqualTo(validEventId);
        assertThat(eventDetails.availablePlaces()).isEqualTo(numbersOfAvailableSeats);
    }
}

package pl.kacper.sales_api.domain.event;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.kacper.sales_api.common.exception.DuplicateDbRecordException;
import pl.kacper.sales_api.domain.event.dto.CreateEventRequestDto;
import pl.kacper.sales_api.domain.message.OutboxMessageRepository;
import tools.jackson.databind.ObjectMapper;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
class EventTransactionServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private OutboxMessageRepository outboxMessageRepository;

    @InjectMocks
    private EventTransactionService eventTransactionService;


    @Test
    @DisplayName("Should saveEventAndMessage() throw DuplicateDbRecordException when passed EventDto with ununique event name")
    void shouldThrowExceptionForUnuniqueEventName() {

        CreateEventRequestDto createEventRequestDto = new CreateEventRequestDto(
                "Test Event",
                null,
                null,
                null,
                0L,
                null,
                40
        );

        Mockito.when(eventRepository.existsByName(createEventRequestDto.name())).thenReturn(true);

        Assertions.assertThatThrownBy(() ->eventTransactionService.saveEventAndMessage(createEventRequestDto))
                .isInstanceOf(DuplicateDbRecordException.class);
    }

    @Test
    @DisplayName("Should createEvent() CreateEventMessageDto contains exactly the same number of places to generate seats that event defined")
    void shouldCreateMessageDtoContainsExactlyTheSameNumberOfPlacesThatEventDefined(){
        CreateEventRequestDto createEventRequestDto = new CreateEventRequestDto(
                null,
                null,
                null,
                null,
                0L,
                null,
                40
        );

        Mockito.when(eventRepository.existsByName(createEventRequestDto.name())).thenReturn(false);


        ArgumentCaptor<EventEntity> eventEntityArgumentCaptor = ArgumentCaptor.forClass(EventEntity.class);
        eventTransactionService.saveEventAndMessage(createEventRequestDto);

        Mockito.verify(eventRepository,Mockito.times(1)).save(eventEntityArgumentCaptor.capture());

        EventEntity eventEntity = eventEntityArgumentCaptor.getValue();

        Assertions.assertThat(createEventRequestDto.placesNumber()).isEqualTo(eventEntity.getPlacesNumber());
    }
}

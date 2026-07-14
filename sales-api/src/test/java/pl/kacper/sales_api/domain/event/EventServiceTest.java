package pl.kacper.sales_api.domain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import pl.kacper.sales_api.domain.event.dto.CreateEventMessageDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventRequestDto;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
public class EventServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private EventService eventService;

    @Test
    @DisplayName("Should Message dto contains exactly the same number of places to generate seats that event defined")
    void shouldMessageDtoContainsExactlyTheSameNumberOfPlacesThatEventDefined(){

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

        Mockito.verify(rabbitTemplate).convertAndSend(ArgumentMatchers.isNull(),ArgumentMatchers.isNull(), captor.capture());

        CreateEventMessageDto messageDto = captor.getValue();

        assertThat(messageDto.placesNumber()).isEqualTo(40);
    }
}

package pl.kacper.sales_api.domain.seat;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pl.kacper.sales_api.domain.event.EventEntity;
import pl.kacper.sales_api.domain.event.dto.CreateEventMessageDto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
public class SeatServiceTest {

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private SeatService seatService;

    private static final int BATCH_SIZE = 50;

    @BeforeEach
    void initBatchSize() {
        ReflectionTestUtils.setField(seatService, "batchSize", BATCH_SIZE);
    }

    @Test
    @DisplayName("Should save 20 SeatEntity object for 20 places defined by event")
    void shouldSave20SeatEntitiesFor20EventPlaces() {

        // Given
        final int placesNumber = 20;
        Long eventId = 1L;
        CreateEventMessageDto createEventMessageDto = new CreateEventMessageDto(
                eventId,
                200,
                placesNumber,
                null
        );
        EventEntity eventEntity = new EventEntity();
        ReflectionTestUtils.setField(eventEntity, "eventId", eventId);

        // When
        Mockito.when(entityManager.getReference(EventEntity.class, eventId)).thenReturn(eventEntity);

        // Then
        seatService.createSeatsByEvent(createEventMessageDto);

        ArgumentCaptor<SeatEntity> seatEntityArgumentCaptor = ArgumentCaptor.forClass(SeatEntity.class);

        Mockito.verify(entityManager, Mockito.times(placesNumber)).persist(seatEntityArgumentCaptor.capture());

        List<SeatEntity> allValues = seatEntityArgumentCaptor.getAllValues();
        for (SeatEntity capturedSeatEntity : allValues) {
            assertThat(capturedSeatEntity.getEvent().getEventId()).isEqualTo(createEventMessageDto.eventId());
            assertThat(capturedSeatEntity.getPrice()).isEqualTo(createEventMessageDto.pricePerSeat());
        }
    }

    @Test
    @DisplayName("Should execute flush() mechanism only one time if number of elements is lower than batch size")
    void shouldExecFlushOnly1TimeForPlacesNumberLowerThanBatchSize() {

        final int placesNumber = 20;
        CreateEventMessageDto createEventMessageDto = new CreateEventMessageDto(
                1L,
                0,
                placesNumber,
                null
        );

        seatService.createSeatsByEvent(createEventMessageDto);

        Mockito.verify(entityManager, Mockito.times(1)).flush();
    }

    @Test
    @DisplayName("Should execute flush(0 mechanism execute exactly 3 times if number of elements is at least 2 times " +
            "higher than batch size but not higher than 3 times of batch size")
    void shouldExecFlush3TimesForPlacesNumberHigher2TimesThanBatchSize(){
        // 2 * batch_size + n < 3 * batch_size
        final int placesNumber = BATCH_SIZE * 2 + 30;
        CreateEventMessageDto createEventMessageDto = new CreateEventMessageDto(
                1L,
                0,
                placesNumber,
                null
        );

        seatService.createSeatsByEvent(createEventMessageDto);

        Mockito.verify(entityManager, Mockito.times(3)).flush();
    }
}

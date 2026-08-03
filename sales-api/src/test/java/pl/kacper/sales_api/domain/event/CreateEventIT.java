package pl.kacper.sales_api.domain.event;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import pl.kacper.sales_api.domain.BaseIT;
import pl.kacper.sales_api.domain.event.dto.Address;
import pl.kacper.sales_api.domain.event.dto.CreateEventRequestDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventResponseDto;
import pl.kacper.sales_api.domain.message.OutboxMessageRepository;
import pl.kacper.sales_api.domain.message.ProcessedMessageRepository;
import pl.kacper.sales_api.domain.message.property.MessageStatus;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;

import java.time.Duration;
import java.time.Instant;


public class CreateEventIT extends BaseIT {

    private final SeatRepository seatRepository;
    private final ProcessedMessageRepository processedMessageRepository;
    private final EventRepository eventRepository;
    private final OutboxMessageRepository outboxMessageRepository;

    @Autowired
    public CreateEventIT(SeatRepository seatRepository, ProcessedMessageRepository processedMessageRepository, EventRepository eventRepository, OutboxMessageRepository outboxMessageRepository) {
        this.seatRepository = seatRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.eventRepository = eventRepository;
        this.outboxMessageRepository = outboxMessageRepository;
    }

    @Test
    @WithMockUser(username = "admintest@gmail.com", password = "Testpass123", roles = "ADMIN")
    void shouldCreateEventAndProcessItThroughMessagingFlow() throws Exception {

        CreateEventRequestDto createEventRequestDto = new CreateEventRequestDto(
                "test-event",
                "test-description",
                EventCategory.CINEMA,
                new Address("Country", "City", "Street", "NO", "00-000"),
                5_000L,
                Instant.now().plus(Duration.ofDays(2)),
                30
        );

        String jsonContent = objectMapper.writeValueAsString(createEventRequestDto);

        MockHttpServletResponse response = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/admin/event")
                                .accept(MediaType.APPLICATION_JSON)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(jsonContent)

                )
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.eventId").exists())
                .andReturn().getResponse();

        String createEventResponseRaw = response.getContentAsString();
        CreateEventResponseDto createEventResponseDto = objectMapper.readValue(createEventResponseRaw, CreateEventResponseDto.class);
        Long eventId = createEventResponseDto.eventId();

        int expectedProcessedMessageRecordCount = 1;
        int expectedSentMessageRecordCount = 1;
        long timeoutMillis = 500;
        int attemptsCount = 20;
        int retriesCounter = 0;

        long processedMessageCount = 0;
        long sentMessageCount = 0;
        while (
                ((processedMessageCount = processedMessageRepository.count()) != expectedProcessedMessageRecordCount ||
                        (sentMessageCount = outboxMessageRepository.countByMessageStatusAndAggregateId(MessageStatus.SENT, String.valueOf(eventId))) != expectedSentMessageRecordCount) &&
                                retriesCounter < attemptsCount) {
            try {
                Thread.sleep(Duration.ofMillis(timeoutMillis));
                retriesCounter++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Assertions.fail("Main thread has been interrupted");
            }
        }

        boolean isEventExists = eventRepository.existsById(eventId);
        long seatsCount = seatRepository.countByEvent_EventIdAndSeatStatus(eventId, SeatStatus.AVAILABLE);

        Assertions.assertThat(processedMessageCount).isEqualTo(expectedProcessedMessageRecordCount);
        Assertions.assertThat(sentMessageCount).isEqualTo(expectedSentMessageRecordCount);
        Assertions.assertThat(isEventExists).isTrue();
        Assertions.assertThat(seatsCount).isEqualTo(createEventRequestDto.placesNumber());
    }
}

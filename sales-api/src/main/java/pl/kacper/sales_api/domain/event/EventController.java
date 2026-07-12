package pl.kacper.sales_api.domain.event;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.kacper.sales_api.domain.event.dto.CreateEventRequestDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventResponseDto;

@RestController
@RequestMapping("/api/v1/")
public class EventController {

    private final EventService eventService;

    @Autowired
    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping("/admin/event")
    public ResponseEntity<CreateEventResponseDto> createEvent(@RequestBody @Valid CreateEventRequestDto createEventRequestDto){

        CreateEventResponseDto eventResponse = eventService.createEvent(createEventRequestDto);

        return ResponseEntity.status(HttpStatus.CREATED).body(eventResponse);
    }
}

package pl.kacper.sales_api.domain.event;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.kacper.sales_api.domain.dto.ElementsPageDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventRequestDto;
import pl.kacper.sales_api.domain.event.dto.CreateEventResponseDto;
import pl.kacper.sales_api.domain.event.dto.SimpleEventDto;

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

    @GetMapping("/event")
    public ResponseEntity<ElementsPageDto<SimpleEventDto>> getEvents(
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "page", required = false, defaultValue = "1") int page
    ){
        ElementsPageDto<SimpleEventDto> events = eventService.getEvents(city, page);
        return ResponseEntity.ok(events);
    }
}

package pl.kacper.sales_api.domain.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import pl.kacper.sales_api.domain.BaseEntity;
import pl.kacper.sales_api.domain.event.dto.Address;

import java.time.Instant;

@NoArgsConstructor
@Getter

@Entity
@Table(name = "events")
public class EventEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "eventGen")
    @SequenceGenerator(name = "eventGen", sequenceName = "events_seq", allocationSize = 50)
    @Column(name = "event_id")
    private Long eventId;

    @Column(unique = true)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    private EventCategory eventCategory;

    @Embedded
    private Address location;

    private Instant eventDate;

    private int placesNumber;


    public EventEntity(String name, String description, EventCategory eventCategory, Address location, Instant eventDate, int placesNumber) {
        this.name = name;
        this.description = description;
        this.eventCategory = eventCategory;
        this.location = location;
        this.eventDate = eventDate;
        this.placesNumber = placesNumber;
    }

}

package pl.kacper.sales_api.domain.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import pl.kacper.sales_api.domain.BaseEntity;
import pl.kacper.sales_api.domain.event.dto.Address;

import java.math.BigInteger;
import java.time.LocalDateTime;

@NoArgsConstructor
@Getter

@Entity
@Table(name = "events")
@EnableJpaAuditing
public class EventEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "eventGen")
    @SequenceGenerator(name = "eventGen", sequenceName = "eventSeq", allocationSize = 50)
    @Column(name = "event_id")
    private Long eventId;

    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    private EventCategory eventCategory;

    @Embedded
    private Address location;

    private LocalDateTime eventDate;

    private int placesNumber;


    public EventEntity(String name, String description, EventCategory eventCategory, Address location, LocalDateTime eventDate, int placesNumber) {
        this.name = name;
        this.description = description;
        this.eventCategory = eventCategory;
        this.location = location;
        this.eventDate = eventDate;
        this.placesNumber = placesNumber;
    }

}

package pl.kacper.fulfillment_worker.domain.ticket;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import pl.kacper.fulfillment_worker.domain.order.FulfillmentOrderEntity;

import java.time.Instant;
import java.util.UUID;

@NoArgsConstructor
@Getter

@Entity
@Table(name = "tickets")
public class TicketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "ticket_id")
    private UUID ticketId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_order_id", nullable = false)
    private FulfillmentOrderEntity fulfillmentOrderEntity;

    @Column(name = "source_order_item_id", nullable = false, unique = true)
    private Long sourceOrderItemId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "event_name", nullable = false)
    private String eventName;
    @Column(name = "country", nullable = false)
    private String country;
    @Column(name = "city", nullable = false)
    private String city;
    @Column(name = "street", nullable = false)
    private String street;
    @Column(name = "no", nullable = false)
    private String no;
    @Column(name = "postal_code", nullable = false)
    private String postalCode;
    @Column(name = "event_date", nullable = false)
    private Instant eventDate;
    @Column(name = "event_category", nullable = false)
    private String eventCategory;
    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    public TicketEntity(FulfillmentOrderEntity fulfillmentOrderEntity, Long sourceOrderItemId, Instant createdAt, String eventName, String country, String city, String street, String no, String postalCode, Instant eventDate, String eventCategory, String seatNumber) {
        this.fulfillmentOrderEntity = fulfillmentOrderEntity;
        this.sourceOrderItemId = sourceOrderItemId;
        this.createdAt = createdAt;
        this.eventName = eventName;
        this.country = country;
        this.city = city;
        this.street = street;
        this.no = no;
        this.postalCode = postalCode;
        this.eventDate = eventDate;
        this.eventCategory = eventCategory;
        this.seatNumber = seatNumber;
    }
}

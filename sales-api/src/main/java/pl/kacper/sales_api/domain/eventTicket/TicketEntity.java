package pl.kacper.sales_api.domain.eventTicket;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import pl.kacper.sales_api.domain.BaseEntity;
import pl.kacper.sales_api.domain.event.EventEntity;
import pl.kacper.sales_api.domain.order.OrderEntity;
import pl.kacper.sales_api.domain.seat.SeatEntity;

import java.math.BigInteger;
import java.util.UUID;

@NoArgsConstructor
@Getter

@Entity
@Table(name = "tickets")
@EnableJpaAuditing
public class TicketEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "ticket_id")
    private UUID ticketId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private EventEntity event;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private OrderEntity order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id")
    private SeatEntity seat;

    private long price;

    public TicketEntity(long price, SeatEntity seat, EventEntity event) {
        this.price = price;
        this.seat = seat;
        this.event = event;
    }

}


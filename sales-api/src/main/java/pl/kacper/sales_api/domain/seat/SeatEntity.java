package pl.kacper.sales_api.domain.seat;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pl.kacper.sales_api.domain.BaseEntity;
import pl.kacper.sales_api.domain.event.EventEntity;

@NoArgsConstructor
@Getter

@Entity
@Table(name = "seats")
public class SeatEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seatGen")
    @SequenceGenerator(name = "seatGen", sequenceName = "seats_seq", allocationSize = 50)
    @Column(name = "seat_id")
    private Long seatId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private EventEntity event;

    private String seatNumber;

    private long price;

    @Setter
    @Enumerated(EnumType.STRING)
    private SeatStatus seatStatus;


    public SeatEntity(EventEntity event, String seatNumber, long price, SeatStatus seatStatus) {
        this.event = event;
        this.seatNumber = seatNumber;
        this.price = price;
        this.seatStatus = seatStatus;
    }

}

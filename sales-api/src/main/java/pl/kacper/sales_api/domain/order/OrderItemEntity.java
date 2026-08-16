package pl.kacper.sales_api.domain.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pl.kacper.sales_api.domain.BaseEntity;
import pl.kacper.sales_api.domain.event.EventEntity;
import pl.kacper.sales_api.domain.seat.SeatEntity;

@NoArgsConstructor
@Getter

@Entity
@Table(name = "order_items")
public class OrderItemEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "orderItemGen")
    @SequenceGenerator(name = "orderItemGen", sequenceName = "order_items_seq", allocationSize = 50)
    @Column(name = "order_item_id")
    private Long orderItemId;

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

    public OrderItemEntity(long price, SeatEntity seat, EventEntity event) {
        this.price = price;
        this.seat = seat;
        this.event = event;
    }

}


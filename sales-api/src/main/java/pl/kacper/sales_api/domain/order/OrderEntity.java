package pl.kacper.sales_api.domain.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import pl.kacper.sales_api.domain.BaseEntity;
import pl.kacper.sales_api.domain.eventTicket.TicketEntity;
import pl.kacper.sales_api.domain.user.UserEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@NoArgsConstructor
@Getter

@Entity
@Table(name = "orders")
@EnableJpaAuditing
public class OrderEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "order_id")
    private UUID orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity purchaser;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "order")
    private List<TicketEntity> ticketList = new ArrayList<>();

    @Column(unique = true)
    private String paymentSessionId;

    @Enumerated(EnumType.STRING)
    private OrderStatus orderStatus;

    private long price;


    public OrderEntity(UserEntity purchaser, String paymentSessionId, OrderStatus orderStatus, long price) {
        this.purchaser = purchaser;
        this.paymentSessionId = paymentSessionId;
        this.orderStatus = orderStatus;
        this.price = price;
    }

    public void addTicketToOrder(TicketEntity ticketEntity){
        ticketEntity.setOrder(this);
        this.ticketList.add(ticketEntity);
    }
}

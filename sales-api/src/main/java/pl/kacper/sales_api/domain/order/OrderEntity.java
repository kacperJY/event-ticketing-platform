package pl.kacper.sales_api.domain.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pl.kacper.sales_api.domain.BaseEntity;
import pl.kacper.sales_api.domain.user.UserEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@NoArgsConstructor
@Getter

@Entity
@Table(name = "orders")
public class OrderEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "order_id")
    private UUID orderId;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity purchaser;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "order", cascade = CascadeType.PERSIST)
    private List<OrderItemEntity> orderItemList = new ArrayList<>();

    // PAYMENT_INTENT
    @Setter
    @Column(unique = true, nullable = true)
    private String stripePaymentIntentId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus orderStatus;

    @Setter
    private long totalAmount;

    @Column(length = 3)
    @Enumerated(EnumType.STRING)
    private CurrencyType currencyType = CurrencyType.PLN;

    @Setter
    @Column(nullable = true)
    private Instant paymentInitializedAt;

    @Setter
    @Column(nullable = true)
    private Instant paidAt;

    @Setter
    @Column(nullable = false)
    private Instant expiresAt;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus paymentStatus;

    public void addOrderItem(OrderItemEntity orderItemEntity){
        orderItemEntity.setOrder(this);
        this.orderItemList.add(orderItemEntity);
    }
}

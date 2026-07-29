package pl.kacper.sales_api.domain.message;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pl.kacper.sales_api.domain.message.property.AggregateType;
import pl.kacper.sales_api.domain.message.property.MessagePayloadVersion;
import pl.kacper.sales_api.domain.message.property.MessageStatus;
import pl.kacper.sales_api.domain.message.property.OperationType;

import java.time.Instant;
import java.util.UUID;

@Getter
@NoArgsConstructor

@Entity
@Table(name = "outbox_messages")
public class OutboxMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "message_id")
    private UUID messageId;

    private String exchange;

    private String routingKey;

    private String aggregateId;

    @Enumerated(EnumType.STRING)
    private AggregateType aggregateType;

    @Enumerated(EnumType.STRING)
    private OperationType operationType;

    @Enumerated(EnumType.STRING)
    private MessagePayloadVersion payloadVersion;

    private String payload;

    @Setter
    @Enumerated(EnumType.STRING)
    private MessageStatus messageStatus;

    private Instant createdAt;

    @Setter
    private Instant sentAt;

    @Setter
    private Instant lockedAt;
    @Setter
    private int retryCount;
    @Setter
    private Instant nextAttemptAt;

    @Version
    private Long version;

    public OutboxMessageEntity(String payload, MessagePayloadVersion payloadVersion, OperationType operationType, AggregateType aggregateType, String aggregateId, String exchange, String routingKey) {
        this.payload = payload;
        this.payloadVersion = payloadVersion;
        this.operationType = operationType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.exchange = exchange;
        this.routingKey = routingKey;

        this.retryCount = 0;
        this.messageStatus = MessageStatus.PENDING;
        Instant timeStamp = Instant.now();
        this.nextAttemptAt = timeStamp;
        this.createdAt = timeStamp;
    }

}

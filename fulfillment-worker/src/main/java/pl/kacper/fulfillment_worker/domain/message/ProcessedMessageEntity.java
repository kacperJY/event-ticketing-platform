package pl.kacper.fulfillment_worker.domain.message;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@NoArgsConstructor
@Getter

@Entity
@Table(name = "processed_messages")
public class ProcessedMessageEntity {

    @EmbeddedId
    @AttributeOverrides(
            {
                    @AttributeOverride(name = "consumerName", column = @Column(name = "consumer_name")),
                    @AttributeOverride(name = "messageId", column = @Column(name = "message_id"))
            }
    )
    private ProcessedMessageId processedMessageId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedMessageEntity(ProcessedMessageId processedMessageId, Instant processedAt) {
        this.processedMessageId = processedMessageId;
        this.processedAt = processedAt;
    }
}

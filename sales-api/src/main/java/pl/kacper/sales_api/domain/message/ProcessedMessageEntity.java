package pl.kacper.sales_api.domain.message;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@NoArgsConstructor
@Getter

@Entity
@Table(name = "processed_messages")
public class ProcessedMessageEntity {

    @EmbeddedId
    private ProcessedMessageId processedMessageId;

    private Instant processedAt;

    public ProcessedMessageEntity(ProcessedMessageId processedMessageId, Instant processedAt) {
        this.processedMessageId = processedMessageId;
        this.processedAt = processedAt;
    }
}

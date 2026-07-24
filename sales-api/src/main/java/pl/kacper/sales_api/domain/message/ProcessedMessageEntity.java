package pl.kacper.sales_api.domain.message;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "processed_messages")
public class ProcessedMessageEntity {

    @EmbeddedId
    private ProcessedMessageId processedMessageId;

    private Instant processedAt;
}

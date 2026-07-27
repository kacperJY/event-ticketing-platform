package pl.kacper.sales_api.domain.message;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedMessageRepository extends ListCrudRepository<ProcessedMessageEntity, ProcessedMessageId> {

    @Modifying
    @Query(
            value = "insert into processed_messages (message_id, consumer_name, processed_at) values (:messageId, :consumerName, :processedAt) on conflict (message_id, consumer_name) do nothing",
            nativeQuery = true)
    int insertOnConflictDoNothing(@Param("messageId") UUID messageId, @Param("consumerName") String consumerName, @Param("processedAt")Instant processedAt);
}

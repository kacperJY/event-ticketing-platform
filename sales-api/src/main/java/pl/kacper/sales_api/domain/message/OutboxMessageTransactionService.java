package pl.kacper.sales_api.domain.message;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.common.exception.NoSuchDbRecordException;
import pl.kacper.sales_api.domain.message.property.MessageStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxMessageTransactionService {

    private final OutboxMessageRepository outboxMessageRepository;

    public OutboxMessageTransactionService(OutboxMessageRepository outboxMessageRepository) {
        this.outboxMessageRepository = outboxMessageRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Collection<OutboxMessageEntity> scanAndMarkProcessing(int fetchSize) {
        if (fetchSize <= 0) return Collections.emptyList();

        PageRequest pageRequest = PageRequest.of(
                0,
                fetchSize,
                Sort.by("nextAttemptAt").ascending()
                        .and(Sort.by("createdAt").ascending()));
        List<OutboxMessageEntity> byStatusAndNextAttemptAt = outboxMessageRepository.findByStatusAndNextAttemptAtWithLockingSkip(MessageStatus.PENDING, Instant.now(), pageRequest);

        byStatusAndNextAttemptAt.forEach(outboxMessageEntity -> {
            outboxMessageEntity.setMessageStatus(MessageStatus.PROCESSING);
            outboxMessageEntity.setLockedAt(Instant.now());
        });

        return byStatusAndNextAttemptAt;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboxMessageEntity markPendingMessageAsProcessing(UUID messageId) {
        OutboxMessageEntity outboxMessageEntity = outboxMessageRepository.findByStatusAndIDWithLockingNoWait(messageId, MessageStatus.PENDING)
                .orElseThrow(() -> new NoSuchDbRecordException("Message with ID=%s does not exist".formatted(messageId)));

        outboxMessageEntity.setMessageStatus(MessageStatus.PROCESSING);
        outboxMessageEntity.setLockedAt(Instant.now());

        return outboxMessageEntity;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Collection<OutboxMessageEntity> requeueProcessingMessages(Instant timeout, int fetchSize) {
        PageRequest pageRequest = PageRequest.of(0, fetchSize, Sort.by("lockedAt").ascending());
        List<OutboxMessageEntity> stuckedInProcessingList = outboxMessageRepository.findStuckInProcessingWithLockingSkip(timeout, pageRequest);
        stuckedInProcessingList.forEach(stuckEntity -> {
            stuckEntity.setMessageStatus(MessageStatus.PENDING);
            stuckEntity.setLockedAt(null);
        });
        return stuckedInProcessingList;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(OutboxMessageEntity outboxMessageEntity) {
        outboxMessageRepository.save(outboxMessageEntity);
    }
}

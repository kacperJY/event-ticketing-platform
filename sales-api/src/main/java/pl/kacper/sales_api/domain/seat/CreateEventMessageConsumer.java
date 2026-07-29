package pl.kacper.sales_api.domain.seat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.common.exception.InvalidMessageFormatException;
import pl.kacper.sales_api.common.exception.RoutingException;
import pl.kacper.sales_api.domain.message.MessageMetadataResolver;
import pl.kacper.sales_api.domain.message.ProcessedMessageId;
import pl.kacper.sales_api.domain.message.ProcessedMessageRepository;
import pl.kacper.sales_api.domain.message.dto.MessageType;
import pl.kacper.sales_api.domain.message.dto.event.CreateEventMessagePayloadDto;
import pl.kacper.sales_api.domain.message.property.AggregateType;
import pl.kacper.sales_api.domain.message.property.MessagePayloadVersion;
import pl.kacper.sales_api.domain.message.property.OperationType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Component
public class CreateEventMessageConsumer  {

    @Value("${rabbitmq.sales-api.create-event.queue-name}")
    private String createEventQueueName;

    @Value("${rabbitmq.sales-api.create-event.consumer-name}")
    private String consumerName;

    private final ProcessedMessageRepository processedMessageRepository;
    private final ObjectMapper objectMapper;
    private final SeatService seatService;

    private final static Logger LOGGER = LoggerFactory.getLogger(CreateEventMessageConsumer.class);
    private static final MessageType EXPECTED_MESSAGE_TYPE = new MessageType(AggregateType.EVENT, OperationType.CREATE, MessagePayloadVersion.V1);
    private static final Class<CreateEventMessagePayloadDto> EXPECTED_PAYLOAD_CLASS = CreateEventMessagePayloadDto.class;


    public CreateEventMessageConsumer(ProcessedMessageRepository processedMessageRepository, ObjectMapper objectMapper, SeatService seatService ) {
        this.processedMessageRepository = processedMessageRepository;
        this.objectMapper = objectMapper;
        this.seatService = seatService;
    }

    @RabbitListener(queues = "${rabbitmq.sales-api.create-event.queue-name}")
    @Transactional
    public void consumeMessage(Message message){
        UUID messageId = MessageMetadataResolver.requireMessageId(message);

        LOGGER.info("Fetching message: {}", messageId);

        MessageType messageType = MessageMetadataResolver.buildMessageType(message);

        if (!messageType.equals(EXPECTED_MESSAGE_TYPE))
            throw new RoutingException("Cannot resolve type of message [%s] in Queue=[%s]. Expect [%s]"
                    .formatted(messageType, createEventQueueName, EXPECTED_MESSAGE_TYPE));


        ProcessedMessageId processedMessageId = new ProcessedMessageId(consumerName, messageId);

        if(processedMessageRepository.existsById(processedMessageId))
            return;

        CreateEventMessagePayloadDto createEventMessagePayloadDto;
        try{
            createEventMessagePayloadDto = objectMapper.readValue(message.getBody(), EXPECTED_PAYLOAD_CLASS);
        } catch (JacksonException e){
            throw new InvalidMessageFormatException("Cannot resolve message's payload from JSON to %s. Probably invalid format JSON.".formatted(EXPECTED_PAYLOAD_CLASS.getSimpleName()));
        }

        int inserts = processedMessageRepository.insertOnConflictDoNothing(processedMessageId.messageId(),processedMessageId.consumerName(),Instant.now());
        if(inserts == 0)
            return;

        seatService.createSeatsByEvent(createEventMessagePayloadDto);
    }
}

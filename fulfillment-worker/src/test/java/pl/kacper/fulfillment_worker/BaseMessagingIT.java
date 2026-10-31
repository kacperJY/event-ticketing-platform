package pl.kacper.fulfillment_worker;

import org.junit.jupiter.api.AfterEach;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=true")
public class BaseMessagingIT extends BaseIT {

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected RabbitAdmin rabbitAdmin;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    @ServiceConnection
    private static final RabbitMQContainer rabbitMQContainer;

    static {
        rabbitMQContainer = new RabbitMQContainer("rabbitmq:4.3.2-management");
        rabbitMQContainer.start();
    }

    @Value("${rabbitmq.sales-api.completed-order.queue-name}")
    private String completedOrderQueueName;

    @Value("${rabbitmq.fulfillment-worker.completed-order.dlq}")
    private String deadLetterQueueName;

    @AfterEach
    void cleanQueues(){
        rabbitAdmin.purgeQueue(completedOrderQueueName,false);
        rabbitAdmin.purgeQueue(deadLetterQueueName,false);
    }

}

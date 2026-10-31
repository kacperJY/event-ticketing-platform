package pl.kacper.fulfillment_worker.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.retry.RetryPolicySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import pl.kacper.exception.InvalidMessageFormatException;
import pl.kacper.exception.RoutingException;

import java.time.Duration;
import java.util.List;

@Configuration
public class AMQPConfig {

    @Value("${rabbitmq.exchange.dead-letter-exchange}")
    private String deadLetterExchangeName;
    @Value("${rabbitmq.exchange.main-exchange}")
    private String mainExchangeName;

    @Value("${rabbitmq.fulfillment-worker.completed-order.dlq}")
    private String completedOrderDeadLetterQueueName;

    @Value("${rabbitmq.fulfillment-worker.completed-order.dlq-routing-key}")
    private String completedOrderDeadLetterRoutingKey;

    @Value("${rabbitmq.sales-api.completed-order.queue-name}")
    private String completedOrderQueueName;
    @Value("${rabbitmq.sales-api.completed-order.routing-key}")
    private String completedOrderRoutingKey;

    @Value("${rabbitmq.consumer.max-retries}")
    private long maxRetries;
    @Value("${rabbitmq.consumer.multiplier}")
    private double multiplier;
    @Value("${rabbitmq.consumer.max-delay}")
    private int maxDelay;
    @Value("${rabbitmq.consumer.delay}")
    private int delay;

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(deadLetterExchangeName,true,false);
    }
    @Bean
    public TopicExchange mainExchange() {
        return new TopicExchange(mainExchangeName,true, false);
    }

    @Bean
    public Queue completedOrderQueue(){
        return QueueBuilder
                .durable(completedOrderQueueName)
                .deadLetterExchange(deadLetterExchangeName)
                .deadLetterRoutingKey(completedOrderDeadLetterRoutingKey)
                .build();
    }
    @Bean
    public Binding completedOrderBinding() {
        return BindingBuilder
                .bind(completedOrderQueue())
                .to(mainExchange())
                .with(completedOrderRoutingKey);
    }


    @Bean
    public Queue completedOrderDeadLetterQueue() {
        return QueueBuilder
                .durable(completedOrderDeadLetterQueueName)
                .build();
    }
    @Bean
    public Binding orderCompletedDeadLetterQueueBinding() {
        return BindingBuilder
                .bind(completedOrderDeadLetterQueue())
                .to(deadLetterExchange())
                .with(completedOrderDeadLetterRoutingKey);
    }

    @Bean
    public StatelessRetryOperationsInterceptor statelessRetryOperationsInterceptor() {
        RetryPolicySettings retryPolicySettings = new RetryPolicySettings();
        retryPolicySettings.setMaxRetries(maxRetries);
        retryPolicySettings.setDelay(Duration.ofSeconds(delay));
        retryPolicySettings.setMultiplier(multiplier);
        retryPolicySettings.setMaxDelay(Duration.ofSeconds(maxDelay));
        retryPolicySettings.setExceptionExcludes(List.of(InvalidMessageFormatException.class, RoutingException.class));
        RetryPolicy retryPolicy = retryPolicySettings.createRetryPolicy();

        return RetryInterceptorBuilder.stateless()
                .retryPolicy(retryPolicy)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    @Bean(name = "rabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory, SimpleRabbitListenerContainerFactoryConfigurer simpleRabbitListenerContainerFactoryConfigurer) {

        SimpleRabbitListenerContainerFactory simpleRabbitListenerContainerFactory = new SimpleRabbitListenerContainerFactory();

        simpleRabbitListenerContainerFactoryConfigurer.configure(simpleRabbitListenerContainerFactory, connectionFactory);

        simpleRabbitListenerContainerFactory.setAdviceChain(statelessRetryOperationsInterceptor());
        simpleRabbitListenerContainerFactory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        simpleRabbitListenerContainerFactory.setDefaultRequeueRejected(false);

        return simpleRabbitListenerContainerFactory;
    }
}

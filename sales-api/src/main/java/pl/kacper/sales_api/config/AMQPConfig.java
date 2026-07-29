package pl.kacper.sales_api.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import pl.kacper.sales_api.common.exception.InvalidMessageFormatException;
import pl.kacper.sales_api.common.exception.RoutingException;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableRabbit
public class AMQPConfig {

    @Value("${rabbitmq.exchange.main-exchange}")
    private String mainExchangeName;
    @Value("${rabbitmq.exchange.dead-letter-exchange}")
    private String deadLetterExchangeName;

    @Value("${rabbitmq.sales-api.create-event.queue-name}")
    private String createEventQueueName;
    @Value("${rabbitmq.sales-api.create-event.dlq}")
    private String createEventDLQ;

    @Value("${rabbitmq.sales-api.create-event.routing-key}")
    private String createEventRoutingKey;
    @Value("${rabbitmq.sales-api.create-event.dlq-routing-key}")
    private String createEventDLQRoutingKey;

    @Value("${rabbitmq.consumer.delay}")
    private long delay;
    @Value("${rabbitmq.consumer.max-delay}")
    private long maxDelay;
    @Value("${rabbitmq.consumer.multiplier}")
    private double multiplier;
    @Value("${rabbitmq.consumer.max-retries}")
    private long maxRetries;


    @Bean
    public TopicExchange mainExchange() {
        return new TopicExchange(mainExchangeName, true, false);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(deadLetterExchangeName, true, false);
    }


    @Bean
    public Queue createEventQueue() {
        return QueueBuilder
                .durable(createEventQueueName)
                .withArgument("x-dead-letter-exchange", deadLetterExchangeName)
                .withArgument("x-dead-letter-routing-key", createEventDLQRoutingKey)
                .build();
    }

    @Bean
    public Binding createEventBinding() {
        return BindingBuilder
                .bind(createEventQueue())
                .to(mainExchange())
                .with(createEventRoutingKey);
    }

    @Bean
    public Queue createEventDeadLetterQueue() {
        return QueueBuilder.durable(createEventDLQ).build();
    }

    @Bean
    public Binding createEventDLQBinding() {
        return BindingBuilder
                .bind(createEventDeadLetterQueue())
                .to(deadLetterExchange())
                .with(createEventDLQRoutingKey);
    }

    @Bean
    public StatelessRetryOperationsInterceptor statelessRetryOperationsInterceptor() {
        RetryPolicy retryPolicy = RetryPolicy.builder().
                excludes(List.of(InvalidMessageFormatException.class, RoutingException.class))
                .maxRetries(maxRetries)
                .delay(Duration.ofSeconds(delay))
                .maxDelay(Duration.ofSeconds(maxDelay))
                .multiplier(multiplier)
                .build();

        return RetryInterceptorBuilder.stateless()
                .retryPolicy(retryPolicy)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            StatelessRetryOperationsInterceptor interceptor,
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer simpleRabbitListenerContainerFactoryConfigurer) {

        SimpleRabbitListenerContainerFactory simpleRabbitListenerContainerFactory = new SimpleRabbitListenerContainerFactory();

        simpleRabbitListenerContainerFactoryConfigurer.configure(simpleRabbitListenerContainerFactory, connectionFactory);

        simpleRabbitListenerContainerFactory.setAdviceChain(interceptor);

        return simpleRabbitListenerContainerFactory;
    }

}

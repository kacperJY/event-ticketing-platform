package pl.kacper.sales_api.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.support.converter.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.kacper.sales_api.domain.event.dto.CreateEventMessageDto;

import java.util.Map;

@Configuration
@EnableRabbit
public class AMQPConfig {

    @Value("${rabbitmq.exchange-name.exchange}")
    private String exchangeName;

    @Value("${rabbitmq.sales-api.queue-name.create-event}")
    private String createEventQueueName;

    @Value("${rabbitmq.sales-api.routing-key.create-event}")
    private String createEventRoutingKey;

    @Bean
    public TopicExchange topicExchange(){
        return new TopicExchange(exchangeName,true,false);
    }

    @Bean
    public Queue createEvnetQueue(){
        return new Queue(createEventQueueName,true);
    }

    @Bean
    public Binding createEventBinding(){
        return BindingBuilder
                .bind(createEvnetQueue())
                .to(topicExchange())
                .with(createEventRoutingKey);
    }

    @Bean
    public JacksonJsonMessageConverter jsonMessageConverter() {
        DefaultJacksonJavaTypeMapper classMapper = new DefaultJacksonJavaTypeMapper();

        classMapper.setTrustedPackages("pl.kacper.sales_api.domain.event.dto");

        classMapper.setIdClassMapping(
                Map.of(
                        "create-event-message", CreateEventMessageDto.class
                )
        );

        JacksonJsonMessageConverter jacksonJsonMessageConverter = new JacksonJsonMessageConverter();
        jacksonJsonMessageConverter.setClassMapper(classMapper);

        return jacksonJsonMessageConverter;
    }
}

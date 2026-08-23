package com.github.comui520.learnhub.knowledge.config.rabbitmq;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocumentParseRabbitMqConfig {

    public static final String EXCHANGE = "learnhub.document.exchange";
    public static final String ROUTING_KEY = "document.parse";
    public static final String PARSE_QUEUE = "learnhub.document.parse.queue";
    public static final String DLX = "learnhub.dlx";
    public static final String DLQ = "learnhub.document.parse.dlq";

    @Bean
    public DirectExchange documentExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue parseQueue() {
        return QueueBuilder
                .durable(PARSE_QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ)
                .build();
    }

    @Bean
    public DirectExchange parseDeadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue parseDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding parseBinding() {
        return BindingBuilder
                .bind(parseQueue()).to(documentExchange())
                .with(ROUTING_KEY);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder
                .bind(parseDeadLetterQueue()).to(parseDeadLetterExchange())
                .with(DLQ);
    }
}

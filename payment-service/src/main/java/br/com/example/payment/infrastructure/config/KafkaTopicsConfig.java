package br.com.example.payment.infrastructure.config;

import br.com.example.payment.infrastructure.messaging.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Tópicos principais (os de retry e DLQ são criados pelo @RetryableTopic).
 * Declarar o mesmo tópico em mais de um serviço é inofensivo: o KafkaAdmin ignora os que já existem.
 */
@Configuration
public class KafkaTopicsConfig {

    private static final int PARTITIONS = 3;

    @Bean
    KafkaAdmin.NewTopics topics() {
        return new KafkaAdmin.NewTopics(
                topic(Topics.PAYMENT_COMMANDS),
                topic(Topics.PAYMENT_EVENTS),
                topic(Topics.INVENTORY_COMMANDS),
                topic(Topics.INVENTORY_EVENTS),
                topic(Topics.FISCAL_COMMANDS),
                topic(Topics.FISCAL_EVENTS),
                topic(Topics.CHECKOUT_EVENTS));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }
}

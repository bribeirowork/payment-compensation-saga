package br.com.example.inventory.infrastructure.config;

import br.com.example.inventory.application.port.out.*;
import br.com.example.inventory.application.service.InventoryService;
import br.com.example.inventory.infrastructure.messaging.Topics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Clock;

@Configuration
public class InventoryConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    InventoryService inventoryService(InventoryRepository inventory, ReservationRepository reservations,
                                  InventoryEventPublisher publisher, Inbox inbox,
                                  UnitOfWork unitOfWork) {
        return new InventoryService(inventory, reservations, publisher, inbox, unitOfWork);
    }

    @Bean
    KafkaAdmin.NewTopics topics() {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(Topics.INVENTORY_COMMANDS).partitions(3).replicas(1).build(),
                TopicBuilder.name(Topics.INVENTORY_EVENTS).partitions(3).replicas(1).build());
    }
}

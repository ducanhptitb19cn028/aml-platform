package com.alexbank.aiops.decision.infrastructure.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatPublisher {

    private static final String TOPIC = "aiops.service.heartbeat";

    @Value("${spring.application.name:decision-engine}")
    private String serviceName;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AtomicLong eventsProcessed = new AtomicLong(0);

    public void recordEvent() {
        eventsProcessed.incrementAndGet();
    }

    @Scheduled(fixedDelay = 30_000)
    public void publish() {
        Map<String, Object> payload = Map.of(
                "service",         serviceName,
                "timestamp",       Instant.now().toString(),
                "status",          "UP",
                "eventsProcessed", eventsProcessed.get()
        );
        kafkaTemplate.send(TOPIC, serviceName, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Heartbeat publish failed for service={}: {}", serviceName, ex.getMessage());
                    } else {
                        log.debug("Heartbeat published for service={} eventsProcessed={}", serviceName, eventsProcessed.get());
                    }
                });
    }
}

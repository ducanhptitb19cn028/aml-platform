package com.yourbank.aiops.alerting.infrastructure.kafka;

import com.yourbank.aiops.alerting.application.AlertRoutingService;
import com.yourbank.aiops.alerting.domain.Incident;
import com.yourbank.aiops.alerting.infrastructure.store.IncidentStore;
import com.yourbank.aiops.alerting.infrastructure.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentConsumer {

    private final AlertRoutingService alertRoutingService;
    private final IncidentStore       incidentStore;
    private final SseEmitterRegistry  sseRegistry;

    @KafkaListener(topics = "aiops.incidents", groupId = "aiops-alerting-service",
            properties = "spring.json.value.default.type=com.yourbank.aiops.alerting.domain.Incident")
    public void consume(Incident incident) {
        if (incident == null) {
            log.warn("Received null incident — skipping");
            return;
        }
        try {
            incidentStore.add(incident);
            alertRoutingService.route(incident);
            sseRegistry.broadcast("incident", incident);
        } catch (Exception e) {
            log.error("Error routing alert for incidentId={}: {}", incident.incidentId(), e.getMessage(), e);
        }
    }
}

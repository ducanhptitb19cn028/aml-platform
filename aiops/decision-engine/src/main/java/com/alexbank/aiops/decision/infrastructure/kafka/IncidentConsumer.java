package com.alexbank.aiops.decision.infrastructure.kafka;

import com.alexbank.aiops.decision.application.DecisionService;
import com.alexbank.aiops.decision.domain.Incident;
import com.alexbank.aiops.decision.domain.RemediationDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentConsumer {

    private static final String TOPIC_DECISIONS = "aiops.decisions";

    private final DecisionService            decisionService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = "aiops.incidents", groupId = "aiops-decision-engine")
    public void consume(Incident incident) {
        if (incident == null) {
            log.warn("Received null incident — skipping");
            return;
        }
        try {
            RemediationDecision decision = decisionService.decide(incident);
            kafkaTemplate.send(TOPIC_DECISIONS, decision.incidentId(), decision)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish decision for incidentId={}: {}",
                                    incident.incidentId(), ex.getMessage());
                        } else {
                            log.info("Published decision={} for incidentId={}",
                                    decision.decisionId(), incident.incidentId());
                        }
                    });
        } catch (Exception e) {
            log.error("Error deciding on incidentId={}: {}", incident.incidentId(), e.getMessage(), e);
        }
    }
}

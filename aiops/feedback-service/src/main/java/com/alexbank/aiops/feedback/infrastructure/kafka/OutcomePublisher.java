package com.alexbank.aiops.feedback.infrastructure.kafka;

import com.alexbank.aiops.feedback.domain.IncidentOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutcomePublisher {

    private static final String TOPIC_OUTCOMES = "aiops.outcomes";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(IncidentOutcome outcome) {
        kafkaTemplate.send(TOPIC_OUTCOMES, outcome.incidentId(), outcome)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish IncidentOutcome for incidentId={}: {}",
                                outcome.incidentId(), ex.getMessage());
                    } else {
                        log.info("Published IncidentOutcome outcomeId={} label={}",
                                outcome.outcomeId(), outcome.label());
                    }
                });
    }
}

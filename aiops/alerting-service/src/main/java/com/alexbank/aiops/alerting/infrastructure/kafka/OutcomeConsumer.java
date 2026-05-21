package com.alexbank.aiops.alerting.infrastructure.kafka;

import com.alexbank.aiops.alerting.domain.IncidentOutcome;
import com.alexbank.aiops.alerting.infrastructure.store.OutcomeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutcomeConsumer {

    private final OutcomeStore outcomeStore;

    @KafkaListener(topics = "aiops.outcomes", groupId = "aiops-alerting-service",
            properties = "spring.json.value.default.type=com.alexbank.aiops.alerting.domain.IncidentOutcome")
    public void consume(IncidentOutcome outcome) {
        if (outcome == null) return;
        outcomeStore.add(outcome);
        log.debug("Stored outcome outcomeId={} label={}", outcome.outcomeId(), outcome.label());
    }
}

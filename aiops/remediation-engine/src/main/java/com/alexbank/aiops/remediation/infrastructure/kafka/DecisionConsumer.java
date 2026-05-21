package com.alexbank.aiops.remediation.infrastructure.kafka;

import com.alexbank.aiops.remediation.application.RemediationDecision;
import com.alexbank.aiops.remediation.application.RemediationService;
import com.alexbank.aiops.remediation.domain.RemediationRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionConsumer {

    private static final String TOPIC_ACTIONS = "aiops.actions";

    private final RemediationService             remediationService;
    private final KafkaTemplate<String, Object>  kafkaTemplate;

    @KafkaListener(topics = "aiops.decisions", groupId = "aiops-remediation-engine")
    public void consume(RemediationDecision decision) {
        if (decision == null) {
            log.warn("Received null decision — skipping");
            return;
        }
        try {
            RemediationRecord record = remediationService.execute(decision);
            kafkaTemplate.send(TOPIC_ACTIONS, record.decisionId(), record)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish RemediationRecord for decisionId={}: {}",
                                    decision.decisionId(), ex.getMessage());
                        } else {
                            log.info("Published RemediationRecord recordId={} status={}",
                                    record.recordId(), record.status());
                        }
                    });
        } catch (Exception e) {
            log.error("Error processing decision decisionId={}: {}", decision.decisionId(), e.getMessage(), e);
        }
    }
}

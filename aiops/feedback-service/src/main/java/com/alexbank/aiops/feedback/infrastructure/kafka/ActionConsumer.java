package com.yourbank.aiops.feedback.infrastructure.kafka;

import com.yourbank.aiops.feedback.application.OutcomeEvaluationService;
import com.yourbank.aiops.feedback.domain.RemediationRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActionConsumer {

    private final OutcomeEvaluationService outcomeEvaluationService;

    @KafkaListener(topics = "aiops.actions", groupId = "aiops-feedback-service")
    public void consume(RemediationRecord record) {
        if (record == null) {
            log.warn("Received null RemediationRecord — skipping");
            return;
        }
        try {
            log.info("Received action record recordId={} action={} status={}",
                    record.recordId(), record.action(), record.status());
            outcomeEvaluationService.scheduleEvaluation(record);
        } catch (Exception e) {
            log.error("Error scheduling evaluation for recordId={}: {}", record.recordId(), e.getMessage(), e);
        }
    }
}

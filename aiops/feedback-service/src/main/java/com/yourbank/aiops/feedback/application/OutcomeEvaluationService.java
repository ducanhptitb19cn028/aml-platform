package com.yourbank.aiops.feedback.application;

import com.yourbank.aiops.feedback.domain.IncidentOutcome;
import com.yourbank.aiops.feedback.domain.OutcomeLabel;
import com.yourbank.aiops.feedback.domain.RemediationRecord;
import com.yourbank.aiops.feedback.infrastructure.kafka.OutcomePublisher;
import com.yourbank.aiops.feedback.infrastructure.mlflow.MlflowClient;
import com.yourbank.aiops.feedback.infrastructure.prometheus.SloQueryAdapter;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutcomeEvaluationService {

    @Value("${aiops.feedback.evaluation.delay-minutes:10}")
    private long evaluationDelayMinutes;

    private final SloQueryAdapter       sloQueryAdapter;
    private final OutcomePublisher      outcomePublisher;
    private final MlflowClient          mlflowClient;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "outcome-eval");
                t.setDaemon(true);
                return t;
            });

    /**
     * Schedules an SLO evaluation 10 minutes after the remediation completes.
     * Captures the before-state immediately, then waits for the after-state.
     */
    public void scheduleEvaluation(RemediationRecord record) {
        if (record == null || record.targetService() == null) {
            log.warn("scheduleEvaluation called with null record or targetService");
            return;
        }

        String service    = record.targetService();
        double sloBefore  = sloQueryAdapter.querySloScore(service);

        log.info("Scheduling outcome evaluation for decisionId={} service={} sloBefore={}",
                record.decisionId(), service, sloBefore);

        scheduler.schedule(() -> evaluate(record, sloBefore),
                evaluationDelayMinutes, TimeUnit.MINUTES);
    }

    private void evaluate(RemediationRecord record, double sloBefore) {
        String service = record.targetService();
        try {
            double sloAfter = sloQueryAdapter.querySloScore(service);
            double delta    = sloAfter - sloBefore;

            OutcomeLabel label;
            if (delta > 0.01) {
                label = OutcomeLabel.RESOLVED;
            } else if (delta < -0.01) {
                label = OutcomeLabel.DEGRADED_FURTHER;
            } else {
                label = OutcomeLabel.NO_EFFECT;
            }

            IncidentOutcome outcome = new IncidentOutcome(
                    UUID.randomUUID().toString(),
                    record.incidentId(),
                    record.decisionId(),
                    label,
                    sloBefore,
                    sloAfter,
                    Instant.now()
            );

            log.info("Outcome evaluated: decisionId={} service={} label={} sloBefore={} sloAfter={}",
                    record.decisionId(), service, label, sloBefore, sloAfter);

            outcomePublisher.publish(outcome);
            mlflowClient.logOutcome(outcome);

        } catch (Exception e) {
            log.error("Outcome evaluation failed for decisionId={} service={}: {}",
                    record.decisionId(), service, e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

package com.alexbank.aiops.feedback.infrastructure.mlflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.alexbank.aiops.feedback.domain.IncidentOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Slf4j
@Component
public class MlflowClient {

    @Value("${aiops.feedback.mlflow.url:http://mlflow:5000}")
    private String mlflowUrl;

    @Value("${aiops.feedback.mlflow.experiment-id:0}")
    private String experimentId;

    private final RestClient   restClient   = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Logs the incident outcome as a metric run in MLflow.
     * Creates a run, then logs metric + tag.
     */
    public void logOutcome(IncidentOutcome outcome) {
        try {
            // Create a new run
            String runId = createRun(outcome.incidentId());
            if (runId == null) {
                log.warn("Failed to create MLflow run for incidentId={}", outcome.incidentId());
                return;
            }

            // Log the numeric outcome (RESOLVED=1, NO_EFFECT=0, DEGRADED_FURTHER=-1)
            double metricValue = switch (outcome.label()) {
                case RESOLVED         ->  1.0;
                case NO_EFFECT        ->  0.0;
                case DEGRADED_FURTHER -> -1.0;
            };

            logMetric(runId, "outcome_score",    metricValue, outcome.evaluatedAt().toEpochMilli());
            logMetric(runId, "slo_before",       outcome.sloBefore(), outcome.evaluatedAt().toEpochMilli());
            logMetric(runId, "slo_after",        outcome.sloAfter(),  outcome.evaluatedAt().toEpochMilli());
            logMetric(runId, "slo_delta",        outcome.sloAfter() - outcome.sloBefore(),
                    outcome.evaluatedAt().toEpochMilli());

            logTag(runId, "incident_id",  outcome.incidentId());
            logTag(runId, "decision_id",  outcome.decisionId());
            logTag(runId, "outcome_label", outcome.label().name());

            // Terminate the run
            terminateRun(runId, "FINISHED");

            log.info("MLflow outcome logged runId={} incidentId={} label={}",
                    runId, outcome.incidentId(), outcome.label());

        } catch (Exception e) {
            log.error("Failed to log outcome to MLflow for incidentId={}: {}",
                    outcome.incidentId(), e.getMessage(), e);
        }
    }

    private String createRun(String incidentId) {
        try {
            Map<String, Object> body = Map.of(
                    "experiment_id", experimentId,
                    "run_name",      "aiops-outcome-" + incidentId,
                    "tags",          new Object[]{
                            Map.of("key", "mlflow.source.type", "value", "JOB"),
                            Map.of("key", "aiops.service",      "value", "feedback-service")
                    }
            );

            String response = restClient.post()
                    .uri(mlflowUrl + "/api/2.0/mlflow/runs/create")
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            if (response != null) {
                return objectMapper.readTree(response).path("run").path("info").path("run_id").asText(null);
            }
        } catch (RestClientException e) {
            log.warn("MLflow createRun HTTP error: {}", e.getMessage());
        } catch (Exception e) {
            log.error("MLflow createRun error: {}", e.getMessage());
        }
        return null;
    }

    private void logMetric(String runId, String key, double value, long timestampMs) {
        try {
            Map<String, Object> body = Map.of(
                    "run_id",    runId,
                    "key",       key,
                    "value",     value,
                    "timestamp", timestampMs,
                    "step",      0
            );
            restClient.post()
                    .uri(mlflowUrl + "/api/2.0/mlflow/runs/log-metric")
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("MLflow logMetric error key={}: {}", key, e.getMessage());
        }
    }

    private void logTag(String runId, String key, String value) {
        try {
            Map<String, String> body = Map.of("run_id", runId, "key", key, "value", value);
            restClient.post()
                    .uri(mlflowUrl + "/api/2.0/mlflow/runs/set-tag")
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("MLflow logTag error key={}: {}", key, e.getMessage());
        }
    }

    private void terminateRun(String runId, String status) {
        try {
            Map<String, String> body = Map.of("run_id", runId, "status", status);
            restClient.post()
                    .uri(mlflowUrl + "/api/2.0/mlflow/runs/update")
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("MLflow terminateRun error: {}", e.getMessage());
        }
    }
}

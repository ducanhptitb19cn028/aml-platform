package com.yourbank.aiops.collector.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourbank.aiops.collector.domain.MetricSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Converts AIOps pipeline events into MetricSignals so the anomaly-detection
 * pipeline can observe the health of the AIOps services themselves.
 *
 * Topics → signals:
 *   aiops.incidents  → aiops_incidents_detected_total  (value = anomalyScore)
 *   aiops.decisions  → aiops_decisions_total            (value = confidence)
 *   aiops.actions    → aiops_remediations_total         (value = 1.0)
 *   aiops.outcomes   → aiops_outcomes_slo_after         (value = sloAfter)
 *   aml.llm.analysis → aiops_llm_analyses_total         (value = confidence)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiopsEventConsumer {

    private final SignalPublisher signalPublisher;
    private final ObjectMapper    objectMapper = new ObjectMapper();

    @KafkaListener(
            topics  = {"aiops.incidents", "aiops.decisions", "aiops.actions",
                       "aiops.outcomes",  "aml.llm.analysis",
                       "aiops.alerts",    "aiops.features",
                       "aiops.service.heartbeat"},
            groupId = "aiops-telemetry-collector-events"
    )
    public void consume(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = objectMapper.readTree(record.value());
            String   topic = record.topic();

            String service    = deriveService(topic, node);
            String metricName = deriveMetricName(topic);
            double value      = deriveValue(topic, node);
            Map<String, String> labels = deriveLabels(topic, node, record.key());

            MetricSignal signal = new MetricSignal(service, Instant.now(), metricName, value, labels);
            signalPublisher.publishMetric(signal);
            log.debug("Converted AIOps event topic={} service={} metric={} value={}",
                    topic, service, metricName, value);

        } catch (Exception e) {
            log.error("Error processing AIOps event from topic={}: {}", record.topic(), e.getMessage(), e);
        }
    }

    private String deriveService(String topic, JsonNode node) {
        return switch (topic) {
            case "aiops.service.heartbeat" -> node.path("service").asText("unknown");
            case "aiops.incidents"  -> "ml-engine";
            case "aiops.decisions"  -> "decision-engine";
            case "aiops.actions"    -> "remediation-engine";
            case "aiops.outcomes"   -> "feedback-service";
            case "aml.llm.analysis" -> "llm-engine";
            case "aiops.alerts"     -> "alerting-service";
            case "aiops.features"   -> "stream-processor";
            default                 -> "unknown";
        };
    }

    private String deriveMetricName(String topic) {
        return switch (topic) {
            case "aiops.service.heartbeat" -> "aiops_service_heartbeat_total";
            case "aiops.incidents"   -> "aiops_incidents_detected_total";
            case "aiops.decisions"   -> "aiops_decisions_total";
            case "aiops.actions"     -> "aiops_remediations_total";
            case "aiops.outcomes"    -> "aiops_outcomes_slo_after";
            case "aml.llm.analysis"  -> "aiops_llm_analyses_total";
            case "aiops.alerts"      -> "aiops_alerts_fired_total";
            case "aiops.features"    -> "aiops_features_processed_total";
            default                  -> "aiops_events_total";
        };
    }

    private double deriveValue(String topic, JsonNode node) {
        return switch (topic) {
            case "aiops.service.heartbeat" -> node.path("eventsProcessed").asDouble(1.0);
            case "aiops.incidents"   -> node.path("anomalyScore").asDouble(1.0);
            case "aiops.decisions"   -> node.path("confidence").asDouble(1.0);
            case "aiops.actions"     -> 1.0;
            case "aiops.outcomes"    -> node.path("sloAfter").asDouble(1.0);
            case "aml.llm.analysis"  -> node.path("confidence").asDouble(1.0);
            case "aiops.alerts"      -> 1.0;
            case "aiops.features"    -> node.path("sampleCount").asDouble(1.0);
            default                  -> 1.0;
        };
    }

    private Map<String, String> deriveLabels(String topic, JsonNode node, String key) {
        return switch (topic) {
            case "aiops.service.heartbeat" ->
                    Map.of("source_topic", topic,
                           "status",       node.path("status").asText("UP"));
            case "aiops.incidents" -> {
                JsonNode rca = node.path("rootCauseRanking");
                String rootCause = rca.isArray() && rca.size() > 0
                        ? rca.get(0).path("component").asText("unknown")
                        : "unknown";
                yield Map.of("source_topic", topic, "root_cause", rootCause);
            }
            case "aiops.decisions" ->
                    Map.of("source_topic", topic,
                           "action",         node.path("action").asText("unknown"),
                           "target_service", node.path("targetService").asText("unknown"));
            case "aiops.actions" ->
                    Map.of("source_topic",   topic,
                           "action",         node.path("action").asText("unknown"),
                           "status",         node.path("status").asText("unknown"),
                           "target_service", node.path("targetService").asText("unknown"));
            case "aiops.outcomes" ->
                    Map.of("source_topic", topic, "label", node.path("label").asText("unknown"));
            case "aml.llm.analysis" ->
                    Map.of("source_topic", topic, "aml_risk", node.path("amlRisk").asText("MEDIUM"));
            case "aiops.alerts" ->
                    Map.of("source_topic", topic,
                           "severity",         node.path("severity").asText("unknown"),
                           "affected_service", node.path("service").asText("unknown"));
            case "aiops.features" ->
                    Map.of("source_topic", topic, "metric_name", node.path("metricName").asText("unknown"));
            default ->
                    Map.of("source_topic", topic, "event_key", key != null ? key : "");
        };
    }
}

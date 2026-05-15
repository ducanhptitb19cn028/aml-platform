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

@Slf4j
@Component
@RequiredArgsConstructor
public class AmlEventConsumer {

    private final SignalPublisher signalPublisher;
    private final ObjectMapper    objectMapper = new ObjectMapper();

    @KafkaListener(topics = {"aml.alerts.events", "aml.cases.events"}, groupId = "aiops-telemetry-collector")
    public void consume(ConsumerRecord<String, String> record) {
        try {
            String topic   = record.topic();
            String payload = record.value();

            String service    = deriveService(topic);
            String metricName = deriveMetricName(topic);

            // Parse the event to extract any labels
            Map<String, String> labels = Map.of(
                    "source_topic", topic,
                    "event_key", record.key() != null ? record.key() : ""
            );

            // Try to enrich with event-level fields
            try {
                JsonNode node    = objectMapper.readTree(payload);
                String eventType = node.path("eventType").asText(node.path("type").asText("unknown"));
                labels = Map.of(
                        "source_topic", topic,
                        "event_type",   eventType,
                        "event_key",    record.key() != null ? record.key() : ""
                );
            } catch (Exception ignored) {
                // raw payload — use minimal labels
            }

            MetricSignal signal = new MetricSignal(
                    service,
                    Instant.now(),
                    metricName,
                    1.0, // count increment
                    labels
            );

            signalPublisher.publishMetric(signal);
            log.debug("Converted AML event from topic={} to MetricSignal service={}", topic, service);

        } catch (Exception e) {
            log.error("Error processing AML event from topic={}: {}", record.topic(), e.getMessage(), e);
        }
    }

    private String deriveService(String topic) {
        if (topic.contains("alerts")) {
            return "transaction-monitoring";
        } else if (topic.contains("cases")) {
            return "case-management";
        } else if (topic.contains("customers")) {
            return "customer-kyc";
        }
        return "unknown";
    }

    private String deriveMetricName(String topic) {
        if (topic.contains("alerts")) {
            return "aml_kafka_alert_events_total";
        } else if (topic.contains("cases")) {
            return "aml_kafka_case_events_total";
        } else if (topic.contains("customers")) {
            return "aml_kafka_customer_events_total";
        }
        return "aml_kafka_events_total";
    }
}

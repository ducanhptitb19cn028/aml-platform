package com.alexbank.aiops.collector.infrastructure.prometheus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alexbank.aiops.collector.domain.MetricSignal;
import com.alexbank.aiops.collector.infrastructure.kafka.SignalPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PrometheusScraperAdapter {

    private static final List<String> METRICS = List.of(
            "http_server_requests_seconds_count",
            "http_server_requests_seconds_sum",
            "jvm_memory_used_bytes",
            "process_cpu_usage",          // JVM gauge 0-1; covers all Spring Boot AIOps services
            "aml_alerts_raised_total",
            "aml_outbox_dispatched_total"
    );

    private static final List<String> SERVICES = List.of(
            // AML services
            "customer-kyc",
            "transaction-monitoring",
            "case-management",
            // AIOps services
            "telemetry-collector",
            "stream-processor",
            "decision-engine",
            "remediation-engine",
            "alerting-service",
            "feedback-service",
            "ml-engine",
            "llm-engine"
    );

    @Value("${aiops.collector.prometheus.url:http://localhost:9090}")
    private String prometheusUrl;

    private final RestClient restClient = RestClient.create();
    private final SignalPublisher signalPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(fixedDelayString = "${aiops.collector.prometheus.interval-ms:30000}")
    public void scrape() {
        log.debug("Starting Prometheus scrape cycle");
        for (String metric : METRICS) {
            try {
                List<MetricSignal> signals = queryMetric(metric);
                signals.forEach(signalPublisher::publishMetric);
                log.debug("Scraped {} signals for metric={}", signals.size(), metric);
            } catch (Exception e) {
                log.warn("Failed to scrape metric={}: {}", metric, e.getMessage());
            }
        }
    }

    private List<MetricSignal> queryMetric(String metricName) {
        List<MetricSignal> signals = new ArrayList<>();
        try {
            String serviceRegex = SERVICES.stream().collect(Collectors.joining("|"));
            String promQuery    = metricName + "{job=~\"" + serviceRegex + "\"}";

            URI uri = UriComponentsBuilder.fromHttpUrl(prometheusUrl + "/api/v1/query")
                    .queryParam("query", promQuery)
                    .build()
                    .encode()
                    .toUri();

            String response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            if (response == null) {
                return signals;
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("data").path("result");

            if (results.isArray()) {
                for (JsonNode result : results) {
                    JsonNode metricNode = result.path("metric");
                    JsonNode valueNode  = result.path("value");

                    Map<String, String> labels = new HashMap<>();
                    metricNode.fields().forEachRemaining(entry -> labels.put(entry.getKey(), entry.getValue().asText()));

                    String service = labels.getOrDefault("job",
                            labels.getOrDefault("service", "unknown"));

                    double value = 0.0;
                    if (valueNode.isArray() && valueNode.size() > 1) {
                        value = Double.parseDouble(valueNode.get(1).asText());
                    }

                    signals.add(new MetricSignal(service, Instant.now(), metricName, value, labels));
                }
            }
        } catch (RestClientException e) {
            log.warn("HTTP error querying Prometheus for metric={}: {}", metricName, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error querying Prometheus for metric={}: {}", metricName, e.getMessage(), e);
        }
        return signals;
    }
}

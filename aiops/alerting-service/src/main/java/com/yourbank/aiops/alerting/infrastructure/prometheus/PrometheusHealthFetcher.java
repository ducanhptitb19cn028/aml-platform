package com.yourbank.aiops.alerting.infrastructure.prometheus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Queries Prometheus for per-service health scores so that services with no
 * recent anomaly incidents still show a meaningful value.
 *
 * Filters by job name (not namespace) so the queries work even when the
 * Prometheus namespace relabelling hasn't propagated yet.
 *
 * Score = MAX of:
 *   up==1          → floor 0.05  ("alive but idle")
 *   process_cpu_usage           → Java/JVM (already 0-1)
 *   rate(process_cpu_seconds_total[2m]) → Python FastAPI (normalised 0-1)
 */
@Slf4j
@Component
public class PrometheusHealthFetcher {

    private static final Set<String> ALL_SERVICES = Set.of(
            "customer-kyc", "transaction-monitoring", "case-management",
            "telemetry-collector", "stream-processor", "decision-engine",
            "remediation-engine", "alerting-service", "feedback-service",
            "ml-engine", "llm-engine"
    );

    private static final String JOB_REGEX =
            ALL_SERVICES.stream().collect(Collectors.joining("|"));

    @Value("${aiops.prometheus.url:http://prometheus-operated.monitoring.svc.cluster.local:9090}")
    private String prometheusUrl;

    private final RestClient   restClient   = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Double> fetchCpuScores() {
        Map<String, Double> scores = new HashMap<>();
        try {
            // Floor: any scraped+alive service gets exactly 0.05 (up=1 → 0.05, up=0 → 0)
            queryInto("max by (job) (up{job=~\"" + JOB_REGEX + "\"})",
                      scores, 1.0, 0.0, 0.05);
            // Java Spring Boot — process_cpu_usage is already a 0-1 fraction
            queryInto("max by (job) (process_cpu_usage{job=~\"" + JOB_REGEX + "\"})",
                      scores, 1.0, 0.0, 1.0);
            // Python FastAPI — cumulative counter, rate gives fraction-of-core
            queryInto("max by (job) (rate(process_cpu_seconds_total{job=~\"" + JOB_REGEX + "\"}[2m]))",
                      scores, 1.0, 0.0, 1.0);
            log.debug("PrometheusHealthFetcher scores: {}", scores);
        } catch (Exception e) {
            log.warn("PrometheusHealthFetcher unavailable — scores will be incident-only: {}", e.getMessage());
        }
        return scores;
    }

    private void queryInto(String promql, Map<String, Double> target,
                           double maxExpected, double minContribution,
                           double maxContribution) throws Exception {
        URI uri = UriComponentsBuilder.fromHttpUrl(prometheusUrl + "/api/v1/query")
                .queryParam("query", promql)
                .build().encode().toUri();

        String body = restClient.get().uri(uri).retrieve().body(String.class);
        if (body == null) return;

        JsonNode root = objectMapper.readValue(body, JsonNode.class);
        for (JsonNode result : root.path("data").path("result")) {
            String   job = result.path("metric").path("job").asText(null);
            JsonNode val = result.path("value");
            if (job == null || !val.isArray() || val.size() < 2) continue;

            double raw          = val.get(1).asDouble(0.0);
            double score        = Math.min(raw / maxExpected, 1.0);
            double contribution = Math.min(Math.max(score, minContribution), maxContribution);
            target.merge(job, contribution, Math::max);
        }
    }
}

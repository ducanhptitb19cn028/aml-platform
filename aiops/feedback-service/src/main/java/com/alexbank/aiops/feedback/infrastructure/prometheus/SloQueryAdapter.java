package com.alexbank.aiops.feedback.infrastructure.prometheus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Slf4j
@Component
public class SloQueryAdapter {

    @Value("${aiops.feedback.prometheus.url:http://localhost:9090}")
    private String prometheusUrl;

    private final RestClient restClient  = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Queries Prometheus for the p95 latency of a service.
     *
     * @param service the service label value (job name in Prometheus)
     * @return p95 latency in seconds (or a sentinel value on error)
     */
    public double querySloScore(String service) {
        String promQuery = String.format(
                "histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{job=\"%s\"}[5m]))",
                service);
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(prometheusUrl + "/api/v1/query")
                    .queryParam("query", promQuery)
                    .build(true)
                    .toUri();

            String response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            if (response == null) {
                log.warn("Empty Prometheus response for SLO query service={}", service);
                return -1.0;
            }

            JsonNode root  = objectMapper.readTree(response);
            JsonNode result = root.path("data").path("result");

            if (result.isArray() && !result.isEmpty()) {
                JsonNode valueNode = result.get(0).path("value");
                if (valueNode.isArray() && valueNode.size() > 1) {
                    String raw = valueNode.get(1).asText();
                    if ("NaN".equalsIgnoreCase(raw) || "Inf".equalsIgnoreCase(raw)) {
                        return -1.0;
                    }
                    return Double.parseDouble(raw);
                }
            }
        } catch (RestClientException e) {
            log.warn("HTTP error querying SLO for service={}: {}", service, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error querying SLO for service={}: {}", service, e.getMessage(), e);
        }
        return -1.0;
    }

    /**
     * Queries Prometheus for error rate of a service.
     *
     * @param service job label
     * @return error rate as a ratio [0.0, 1.0]
     */
    public double queryErrorRate(String service) {
        String promQuery = String.format(
                "rate(http_server_requests_seconds_count{job=\"%s\",status=~\"5..\"}[5m]) " +
                "/ rate(http_server_requests_seconds_count{job=\"%s\"}[5m])",
                service, service);
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(prometheusUrl + "/api/v1/query")
                    .queryParam("query", promQuery)
                    .build(true)
                    .toUri();

            String response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            if (response == null) return 0.0;

            JsonNode root   = objectMapper.readTree(response);
            JsonNode result = root.path("data").path("result");

            if (result.isArray() && !result.isEmpty()) {
                JsonNode valueNode = result.get(0).path("value");
                if (valueNode.isArray() && valueNode.size() > 1) {
                    String raw = valueNode.get(1).asText();
                    if ("NaN".equalsIgnoreCase(raw) || "Inf".equalsIgnoreCase(raw)) return 0.0;
                    return Double.parseDouble(raw);
                }
            }
        } catch (Exception e) {
            log.warn("Error querying error rate for service={}: {}", service, e.getMessage());
        }
        return 0.0;
    }
}

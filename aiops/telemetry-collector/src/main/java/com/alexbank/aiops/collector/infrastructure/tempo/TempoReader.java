package com.alexbank.aiops.collector.infrastructure.tempo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alexbank.aiops.collector.domain.TraceSignal;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TempoReader {

    private static final List<String> SERVICES = List.of(
            "customer-kyc", "transaction-monitoring", "case-management",
            "telemetry-collector", "stream-processor", "decision-engine",
            "remediation-engine", "alerting-service", "feedback-service",
            "ml-engine", "llm-engine"
    );

    @Value("${aiops.collector.tempo.url:http://localhost:3200}")
    private String tempoUrl;

    private final RestClient restClient = RestClient.create();
    private final SignalPublisher signalPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(fixedDelay = 30_000)
    public void poll() {
        Instant now   = Instant.now();
        Instant start = now.minus(35, ChronoUnit.SECONDS);

        for (String service : SERVICES) {
            try {
                List<TraceSignal> signals = queryTempo(service, start, now);
                signals.forEach(signalPublisher::publishTrace);
                if (!signals.isEmpty()) {
                    log.debug("Ingested {} trace signals from Tempo for service={}", signals.size(), service);
                }
            } catch (Exception e) {
                log.warn("Tempo poll failed for service={}: {}", service, e.getMessage());
            }
        }
    }

    private List<TraceSignal> queryTempo(String service, Instant start, Instant end) {
        List<TraceSignal> signals = new ArrayList<>();
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(tempoUrl + "/api/search")
                    .queryParam("tags", "service.name=" + service)
                    .queryParam("start", start.getEpochSecond())
                    .queryParam("end",   end.getEpochSecond())
                    .queryParam("limit", 10)
                    .build()
                    .encode()
                    .toUri();

            String response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            if (response == null) return signals;

            JsonNode root   = objectMapper.readTree(response);
            JsonNode traces = root.path("traces");

            if (traces.isArray()) {
                for (JsonNode t : traces) {
                    String traceId   = t.path("traceID").asText("");
                    String operation = t.path("rootTraceName").asText("unknown");
                    long   durationMs = t.path("durationMs").asLong(0);
                    String startNs   = t.path("startTimeUnixNano").asText("0");

                    Instant ts;
                    try {
                        ts = Instant.ofEpochSecond(0, Long.parseLong(startNs));
                    } catch (NumberFormatException nfe) {
                        ts = Instant.now();
                    }

                    if (!traceId.isEmpty()) {
                        signals.add(new TraceSignal(service, ts, traceId, "", operation, durationMs, 0));
                    }
                }
            }
        } catch (RestClientException e) {
            log.debug("HTTP error querying Tempo for service={}: {}", service, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error querying Tempo for service={}: {}", service, e.getMessage(), e);
        }
        return signals;
    }
}

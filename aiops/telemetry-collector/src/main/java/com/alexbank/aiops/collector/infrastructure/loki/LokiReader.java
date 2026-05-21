package com.alexbank.aiops.collector.infrastructure.loki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alexbank.aiops.collector.domain.LogSignal;
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
public class LokiReader {

    @Value("${aiops.collector.loki.url:http://localhost:3100}")
    private String lokiUrl;

    private final RestClient restClient = RestClient.create();
    private final SignalPublisher signalPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(fixedDelay = 30_000)
    public void poll() {
        log.debug("Polling Loki for ERROR logs");
        List<LogSignal> logSignals = queryLoki();
        logSignals.forEach(signalPublisher::publishLog);
        if (!logSignals.isEmpty()) {
            log.info("Ingested {} error log signals from Loki", logSignals.size());
        }
    }

    private List<LogSignal> queryLoki() {
        List<LogSignal> signals = new ArrayList<>();
        try {
            Instant now   = Instant.now();
            Instant start = now.minus(30, ChronoUnit.SECONDS);

            URI uri = UriComponentsBuilder.fromHttpUrl(lokiUrl + "/loki/api/v1/query_range")
                    .queryParam("query", "{namespace=~\"aml|aiops\"}|json|level=\"ERROR\"")
                    .queryParam("start", String.valueOf(start.toEpochMilli() * 1_000_000L))
                    .queryParam("end",   String.valueOf(now.toEpochMilli()   * 1_000_000L))
                    .queryParam("limit", 1000)
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

            JsonNode root    = objectMapper.readTree(response);
            JsonNode streams = root.path("data").path("result");

            if (streams.isArray()) {
                for (JsonNode stream : streams) {
                    JsonNode streamLabels = stream.path("stream");
                    String service = streamLabels.path("service_name")
                            .asText(streamLabels.path("app").asText("unknown"));

                    JsonNode values = stream.path("values");
                    if (values.isArray()) {
                        for (JsonNode entry : values) {
                            // entry is [timestamp_ns, log_line]
                            long tsNs      = Long.parseLong(entry.get(0).asText());
                            String logLine = entry.get(1).asText();
                            Instant ts     = Instant.ofEpochSecond(0, tsNs);

                            String traceId = null;
                            String spanId  = null;
                            String level   = "ERROR";
                            String message = logLine;

                            // Attempt to parse JSON log line
                            try {
                                JsonNode logJson = objectMapper.readTree(logLine);
                                traceId = logJson.path("traceId").asText(null);
                                spanId  = logJson.path("spanId").asText(null);
                                level   = logJson.path("level").asText("ERROR");
                                message = logJson.path("message").asText(logLine);
                                if (logJson.has("service")) {
                                    service = logJson.path("service").asText(service);
                                }
                            } catch (Exception ignored) {
                                // plain text log line — use defaults
                            }

                            signals.add(new LogSignal(service, ts, traceId, spanId, level, message));
                        }
                    }
                }
            }
        } catch (RestClientException e) {
            log.warn("HTTP error polling Loki: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error polling Loki: {}", e.getMessage(), e);
        }
        return signals;
    }
}

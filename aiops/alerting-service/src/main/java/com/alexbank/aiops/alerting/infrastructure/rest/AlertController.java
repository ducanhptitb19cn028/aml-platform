package com.yourbank.aiops.alerting.infrastructure.rest;

import com.yourbank.aiops.alerting.domain.Incident;
import com.yourbank.aiops.alerting.domain.IncidentOutcome;
import com.yourbank.aiops.alerting.domain.RemediationRecord;
import com.yourbank.aiops.alerting.infrastructure.prometheus.PrometheusHealthFetcher;
import com.yourbank.aiops.alerting.infrastructure.store.IncidentStore;
import com.yourbank.aiops.alerting.infrastructure.store.OutcomeStore;
import com.yourbank.aiops.alerting.infrastructure.store.RemediationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AlertController {

    private final IncidentStore          incidentStore;
    private final RemediationStore       remediationStore;
    private final OutcomeStore           outcomeStore;
    private final PrometheusHealthFetcher prometheusHealthFetcher;

    @GetMapping("/incidents")
    public List<Incident> incidents(@RequestParam(defaultValue = "50") int limit) {
        return incidentStore.getRecent(limit);
    }

    @GetMapping("/remediations")
    public List<RemediationRecord> remediations(@RequestParam(defaultValue = "50") int limit) {
        return remediationStore.getRecent(limit);
    }

    @GetMapping("/outcomes")
    public List<IncidentOutcome> outcomes() {
        return outcomeStore.getAll();
    }

    /**
     * Derives health status from the most recent incident per service in the last 5 minutes.
     * OK → no recent incidents or score < 0.5
     * WARNING → 0.5 ≤ score < 0.75
     * CRITICAL → score ≥ 0.75
     */
    @GetMapping("/services/health")
    public List<Map<String, Object>> serviceHealth() {
        Instant cutoff = Instant.now().minus(5, ChronoUnit.MINUTES);

        List<Incident> recent = incidentStore.getRecent(200).stream()
                .filter(i -> i.detectedAt() != null && i.detectedAt().isAfter(cutoff))
                .toList();

        // Group by first affected service, pick max anomaly score
        Map<String, Double> maxScoreByService = recent.stream()
                .filter(i -> i.affectedServices() != null && !i.affectedServices().isEmpty())
                .collect(Collectors.toMap(
                        i -> i.affectedServices().get(0),
                        Incident::anomalyScore,
                        Math::max
                ));

        Map<String, String> serviceNamespace = Map.ofEntries(
                Map.entry("customer-kyc",          "aml"),
                Map.entry("transaction-monitoring", "aml"),
                Map.entry("case-management",        "aml"),
                Map.entry("telemetry-collector",    "aiops"),
                Map.entry("stream-processor",       "aiops"),
                Map.entry("decision-engine",        "aiops"),
                Map.entry("remediation-engine",     "aiops"),
                Map.entry("alerting-service",       "aiops"),
                Map.entry("feedback-service",       "aiops"),
                Map.entry("ml-engine",              "aiops"),
                Map.entry("llm-engine",             "aiops")
        );

        // Supplement incident scores with live CPU data from Prometheus so
        // healthy services that have had no recent incidents still show a score.
        Map<String, Double> cpuScores = prometheusHealthFetcher.fetchCpuScores();

        Instant checkedAt = Instant.now();
        return serviceNamespace.entrySet().stream()
                .map(e -> {
                    String svc          = e.getKey();
                    double incidentScore = maxScoreByService.getOrDefault(svc, 0.0);
                    double cpuScore      = cpuScores.getOrDefault(svc, 0.0);
                    double score         = Math.max(incidentScore, cpuScore);
                    String status = score >= 0.75 ? "CRITICAL" : score >= 0.5 ? "WARNING" : "OK";
                    return Map.<String, Object>of(
                            "service",          svc,
                            "namespace",        e.getValue(),
                            "status",           status,
                            "lastAnomalyScore", score,
                            "checkedAt",        checkedAt.toString()
                    );
                })
                .sorted(Comparator.comparing(m -> (String) m.get("service")))
                .collect(Collectors.toList());
    }

    @GetMapping("/outcomes/summary")
    public Map<String, Long> outcomeSummary() {
        return outcomeStore.getAll().stream()
                .collect(Collectors.groupingBy(IncidentOutcome::label, Collectors.counting()));
    }
}

package com.yourbank.aiops.alerting.application;

import com.yourbank.aiops.alerting.domain.Alert;
import com.yourbank.aiops.alerting.domain.Incident;
import com.yourbank.aiops.alerting.domain.Severity;
import com.yourbank.aiops.alerting.infrastructure.webhook.SlackNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRoutingService {

    private static final String TOPIC_ALERTS = "aiops.alerts";

    @Value("${aiops.alerting.runbook.base-url:https://runbooks.internal/aiops}")
    private String runbookBaseUrl;

    private final SlackNotifier                  slackNotifier;
    private final KafkaTemplate<String, Object>  kafkaTemplate;

    /**
     * Maps an incident to a severity, creates an Alert and routes it to the appropriate channel.
     */
    public Alert route(Incident incident) {
        Severity severity = Severity.fromAnomalyScore(incident.anomalyScore());

        String service = primaryService(incident.affectedServices());
        String message = buildMessage(incident, severity);
        String runbook = runbookBaseUrl + "/" + service.replace("-", "_") + ".md";

        Alert alert = new Alert(
                UUID.randomUUID().toString(),
                incident.incidentId(),
                severity.name(),
                service,
                message,
                runbook,
                Instant.now()
        );

        log.info("Alert created alertId={} incidentId={} severity={} service={}",
                alert.alertId(), alert.incidentId(), alert.severity(), alert.service());

        // Route based on severity
        if (severity == Severity.P1 || severity == Severity.P2) {
            slackNotifier.notify(alert);
        } else {
            log.info("Low-severity alert {} ({}) — logged only", alert.alertId(), severity);
        }

        kafkaTemplate.send(TOPIC_ALERTS, alert.service(), alert);

        return alert;
    }

    private String primaryService(List<String> services) {
        if (services == null || services.isEmpty()) return "unknown";
        return services.get(0);
    }

    private String buildMessage(Incident incident, Severity severity) {
        return String.format(
                "Anomaly detected on %s (score=%.3f, confidence=%.2f). " +
                "Breach ETA: %d min. Affected: %s",
                primaryService(incident.affectedServices()),
                incident.anomalyScore(),
                incident.confidence(),
                incident.breachEtaMinutes(),
                incident.affectedServices()
        );
    }
}

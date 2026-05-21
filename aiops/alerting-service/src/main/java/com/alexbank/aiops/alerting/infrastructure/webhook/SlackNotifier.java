package com.alexbank.aiops.alerting.infrastructure.webhook;

import com.alexbank.aiops.alerting.domain.Alert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Slf4j
@Component
public class SlackNotifier {

    @Value("${aiops.alerting.slack.webhook-url:}")
    private String webhookUrl;

    private final RestClient restClient = RestClient.create();

    /**
     * Sends an alert notification to Slack if a webhook URL is configured.
     * Falls back to a WARN log if not configured.
     */
    public void notify(Alert alert) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("[SLACK-STUB] Alert fired — no webhook configured. " +
                    "alertId={} severity={} service={} message={}",
                    alert.alertId(), alert.severity(), alert.service(), alert.message());
            return;
        }

        String text = buildSlackText(alert);
        try {
            restClient.post()
                    .uri(webhookUrl)
                    .header("Content-Type", "application/json")
                    .body(Map.of("text", text))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Slack notification sent alertId={} severity={}", alert.alertId(), alert.severity());
        } catch (RestClientException e) {
            log.error("Failed to send Slack notification for alertId={}: {}", alert.alertId(), e.getMessage());
        }
    }

    private String buildSlackText(Alert alert) {
        return String.format(
                "[%s] *%s* | Service: `%s` | IncidentId: `%s`\n%s\nRunbook: %s",
                alert.severity(),
                alert.severity().equals("P1") ? ":rotating_light: CRITICAL" :
                alert.severity().equals("P2") ? ":warning: HIGH" :
                alert.severity().equals("P3") ? ":large_yellow_circle: MEDIUM" : ":white_circle: LOW",
                alert.service(),
                alert.incidentId(),
                alert.message(),
                alert.runbookUrl() != null ? alert.runbookUrl() : "N/A"
        );
    }
}

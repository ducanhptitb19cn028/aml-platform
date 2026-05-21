package com.yourbank.aiops.alerting.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Alert(
        String alertId,
        String incidentId,
        String severity,
        String service,
        String message,
        String runbookUrl,
        Instant firedAt
) {
}

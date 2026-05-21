package com.alexbank.aiops.alerting.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * Incident DTO consumed from aiops.incidents.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Incident(
        String incidentId,
        Instant detectedAt,
        double anomalyScore,
        List<String> affectedServices,
        List<RootCauseEntry> rootCauseRanking,
        int breachEtaMinutes,
        double confidence
) {
}

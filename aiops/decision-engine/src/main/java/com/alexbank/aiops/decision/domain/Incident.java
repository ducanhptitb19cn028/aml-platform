package com.alexbank.aiops.decision.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

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

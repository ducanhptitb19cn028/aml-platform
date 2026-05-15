package com.yourbank.aiops.alerting.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IncidentOutcome(
        String outcomeId,
        String incidentId,
        String decisionId,
        String label,
        double sloBefore,
        double sloAfter,
        Instant evaluatedAt
) {}

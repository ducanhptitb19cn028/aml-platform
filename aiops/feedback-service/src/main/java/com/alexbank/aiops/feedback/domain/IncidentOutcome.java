package com.alexbank.aiops.feedback.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IncidentOutcome(
        String outcomeId,
        String incidentId,
        String decisionId,
        OutcomeLabel label,
        double sloBefore,
        double sloAfter,
        Instant evaluatedAt
) {
}

package com.yourbank.aiops.remediation.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * DTO matching the decision-engine's RemediationDecision published to aiops.decisions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemediationDecision(
        String decisionId,
        String incidentId,
        String action,
        String targetService,
        double confidence,
        String blastRadius,
        boolean autoExecute,
        String justification,
        Instant decidedAt
) {
}

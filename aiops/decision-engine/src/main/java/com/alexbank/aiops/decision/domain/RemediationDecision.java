package com.yourbank.aiops.decision.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RemediationDecision(
        String decisionId,
        String incidentId,
        ActionType action,
        String targetService,
        double confidence,
        BlastRadius blastRadius,
        boolean autoExecute,
        String justification,
        Instant decidedAt
) {
}

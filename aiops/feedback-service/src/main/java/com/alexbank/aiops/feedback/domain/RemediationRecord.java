package com.alexbank.aiops.feedback.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * DTO mirroring remediation-engine's RemediationRecord published to aiops.actions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemediationRecord(
        String recordId,
        String decisionId,
        String incidentId,
        String action,
        String targetService,
        String targetNamespace,
        String status,
        String preState,
        String postState,
        Instant executedAt
) {
}

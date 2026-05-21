package com.alexbank.aiops.alerting.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

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
) {}

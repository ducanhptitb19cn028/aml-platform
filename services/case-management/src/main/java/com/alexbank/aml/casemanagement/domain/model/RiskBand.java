package com.alexbank.aml.casemanagement.domain.model;

/**
 * Categorical risk band derived from RiskScore.
 * Used as a low-cardinality observability tag and a feature for the
 * anomaly detection model.
 */
public enum RiskBand {
    LOW, MEDIUM, HIGH
}

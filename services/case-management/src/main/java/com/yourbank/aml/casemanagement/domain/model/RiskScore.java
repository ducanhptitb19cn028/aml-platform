package com.yourbank.aml.casemanagement.domain.model;

/**
 * Risk score, validated at construction time.
 * Encapsulates the 0..100 invariant — no other layer needs to know it.
 */
public record RiskScore(int value) {

    public RiskScore {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(
                "Risk score must be between 0 and 100, got: " + value);
        }
    }

    public RiskBand band() {
        if (value < 30) return RiskBand.LOW;
        if (value < 70) return RiskBand.MEDIUM;
        return RiskBand.HIGH;
    }
}

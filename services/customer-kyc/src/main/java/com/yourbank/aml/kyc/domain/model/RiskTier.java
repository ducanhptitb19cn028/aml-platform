package com.yourbank.aml.kyc.domain.model;

/**
 * The customer's overall risk tier, set during KYC and adjusted over
 * time based on behaviour, jurisdiction, PEP status, etc.
 *
 * The tier directly affects how the rest of the platform treats them:
 *   - LOW: standard rule thresholds
 *   - STANDARD: standard rules (default)
 *   - HIGH: tightened thresholds + faster periodic review
 *   - PROHIBITED: bank refuses to onboard or has exited the relationship
 */
public enum RiskTier {
    LOW,
    STANDARD,
    HIGH,
    PROHIBITED;

    public boolean requiresEnhancedDueDiligence() {
        return this == HIGH || this == PROHIBITED;
    }
}

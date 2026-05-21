package com.yourbank.aml.monitoring.infrastructure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The slice of the KYC customer view that Monitoring actually uses.
 *
 * Note we do NOT mirror the producer's full schema — we only carry the
 * fields we need. @JsonIgnoreProperties(ignoreUnknown = true) lets KYC
 * add new fields without breaking us. This is the standard
 * cross-context anti-corruption layer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerProfile(
        String customerId,
        String tier,
        boolean politicallyExposed,
        boolean sanctioned
) {
    public boolean isHighRisk() {
        return "HIGH".equals(tier) || "PROHIBITED".equals(tier);
    }
}

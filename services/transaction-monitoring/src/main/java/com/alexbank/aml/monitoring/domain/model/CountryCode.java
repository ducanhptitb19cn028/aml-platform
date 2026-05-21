package com.yourbank.aml.monitoring.domain.model;

import java.util.Objects;
import java.util.Set;

/**
 * ISO 3166-1 alpha-2 country code. Used for jurisdiction-based rules
 * (high-risk country lists, sanctions geographic scope).
 */
public record CountryCode(String value) {

    /**
     * FATF high-risk and other monitored jurisdictions, simplified.
     * In production this would come from a configuration source that
     * tracks the FATF lists in real time.
     */
    private static final Set<String> HIGH_RISK = Set.of(
            "IR", "KP",          // FATF black list
            "MM", "AF", "SY"     // FATF grey list (sample)
    );

    public CountryCode {
        Objects.requireNonNull(value, "country code is required");
        if (value.length() != 2 || !value.equals(value.toUpperCase())) {
            throw new IllegalArgumentException(
                    "Country code must be ISO 3166-1 alpha-2 uppercase: " + value);
        }
    }

    public boolean isHighRisk() {
        return HIGH_RISK.contains(value);
    }
}

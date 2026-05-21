package com.alexbank.aml.kyc.domain.model;

import java.util.Objects;

/**
 * ISO 3166-1 alpha-2 country code for residency / nationality.
 *
 * Note: this is intentionally a SEPARATE class from
 * com.alexbank.aml.monitoring.domain.model.CountryCode. Bounded
 * contexts do not share types — even when the underlying concept is
 * identical. If KYC ever needs to evolve its country handling
 * (separate residency from nationality, support tax-residency lists,
 * etc.) it does so without affecting Monitoring.
 */
public record CountryCode(String value) {

    public CountryCode {
        Objects.requireNonNull(value, "country code is required");
        if (value.length() != 2 || !value.equals(value.toUpperCase())) {
            throw new IllegalArgumentException(
                    "Country code must be ISO 3166-1 alpha-2 uppercase: " + value);
        }
    }
}

package com.alexbank.aml.kyc.domain.model;

import java.util.Objects;

/**
 * The customer's full risk profile as a single value object.
 *
 * Why a value object: the profile changes as a unit. We never update
 * "just the PEP flag" — a change to any field warrants reassessing the
 * whole profile. By keeping it immutable and replacing wholesale, we
 * get atomic updates and a clean audit trail.
 */
public record RiskProfile(
        RiskTier tier,
        boolean politicallyExposed,
        boolean sanctioned,
        CountryCode residencyCountry
) {
    public RiskProfile {
        Objects.requireNonNull(tier, "tier is required");
        Objects.requireNonNull(residencyCountry, "residencyCountry is required");
        if (sanctioned && tier != RiskTier.PROHIBITED) {
            throw new IllegalArgumentException(
                    "A sanctioned customer must be in PROHIBITED tier; got " + tier);
        }
    }

    /** Standard low-risk profile for a typical retail customer. */
    public static RiskProfile standard(CountryCode residency) {
        return new RiskProfile(RiskTier.STANDARD, false, false, residency);
    }
}

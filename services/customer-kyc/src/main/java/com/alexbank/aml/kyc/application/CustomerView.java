package com.alexbank.aml.kyc.application;

import com.alexbank.aml.kyc.domain.model.Customer;
import com.alexbank.aml.kyc.domain.model.RiskTier;
import com.alexbank.aml.kyc.domain.model.VerificationStatus;

import java.time.Instant;

/**
 * Public read model — what other services see when they query the
 * customer endpoint.
 *
 * Note: this is INTENTIONALLY a slim DTO. The Customer aggregate
 * has more state than this (audit trail, internal verification
 * timestamps), but consumers only need the operationally-relevant
 * fields. Serving a slim view here protects KYC's internal model from
 * accidental coupling.
 */
public record CustomerView(
        String customerId,
        String legalName,
        VerificationStatus status,
        RiskTier tier,
        boolean politicallyExposed,
        boolean sanctioned,
        String residencyCountry,
        Instant lastUpdatedAt
) {
    public static CustomerView from(Customer c) {
        return new CustomerView(
                c.id().asString(),
                c.legalName(),
                c.status(),
                c.riskProfile().tier(),
                c.riskProfile().politicallyExposed(),
                c.riskProfile().sanctioned(),
                c.riskProfile().residencyCountry().value(),
                c.lastUpdatedAt()
        );
    }
}

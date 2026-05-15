package com.yourbank.aml.kyc.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where the customer is in the KYC verification journey.
 *
 * <pre>
 *   PENDING ─────► IN_PROGRESS ─────► VERIFIED
 *      │              │ ▲                │
 *      │              ▼ │                ▼
 *      └──────►  REJECTED          PERIODIC_REVIEW
 *                                       │
 *                                       ▼
 *                                    VERIFIED  (re-affirmed)
 * </pre>
 */
public enum VerificationStatus {
    PENDING,           // newly onboarded, no checks run yet
    IN_PROGRESS,       // documents received, checks running
    VERIFIED,          // all checks passed
    REJECTED,          // failed checks or refused to provide docs
    PERIODIC_REVIEW;   // verified previously, due for re-check

    private Set<VerificationStatus> allowedNext;

    static {
        PENDING.allowedNext = EnumSet.of(IN_PROGRESS, REJECTED);
        IN_PROGRESS.allowedNext = EnumSet.of(VERIFIED, REJECTED);
        VERIFIED.allowedNext = EnumSet.of(PERIODIC_REVIEW, REJECTED);
        REJECTED.allowedNext = EnumSet.noneOf(VerificationStatus.class);
        PERIODIC_REVIEW.allowedNext = EnumSet.of(VERIFIED, REJECTED, IN_PROGRESS);
    }

    public boolean canTransitionTo(VerificationStatus target) {
        return allowedNext.contains(target);
    }

    public boolean isTerminal() {
        return this == REJECTED;
    }

    public boolean isVerified() {
        return this == VERIFIED;
    }
}

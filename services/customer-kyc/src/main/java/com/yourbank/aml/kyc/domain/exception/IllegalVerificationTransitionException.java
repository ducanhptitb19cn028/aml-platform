package com.yourbank.aml.kyc.domain.exception;

import com.yourbank.aml.kyc.domain.model.VerificationStatus;

public class IllegalVerificationTransitionException extends RuntimeException {
    public IllegalVerificationTransitionException(VerificationStatus from, VerificationStatus to) {
        super("Cannot transition KYC verification from %s to %s".formatted(from, to));
    }
}

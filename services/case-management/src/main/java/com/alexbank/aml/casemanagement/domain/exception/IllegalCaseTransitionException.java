package com.yourbank.aml.casemanagement.domain.exception;

import com.yourbank.aml.casemanagement.domain.model.CaseStatus;

public class IllegalCaseTransitionException extends RuntimeException {
    public IllegalCaseTransitionException(CaseStatus from, CaseStatus to) {
        super("Cannot transition case from %s to %s".formatted(from, to));
    }
}

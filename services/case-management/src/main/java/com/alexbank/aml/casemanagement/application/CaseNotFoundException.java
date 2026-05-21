package com.yourbank.aml.casemanagement.application;

import com.yourbank.aml.casemanagement.domain.model.CaseId;

public class CaseNotFoundException extends RuntimeException {
    public CaseNotFoundException(CaseId id) {
        super("Case not found: " + id.asString());
    }
}

package com.alexbank.aml.casemanagement.application;

import com.alexbank.aml.casemanagement.domain.model.CaseId;

public class CaseNotFoundException extends RuntimeException {
    public CaseNotFoundException(CaseId id) {
        super("Case not found: " + id.asString());
    }
}

package com.yourbank.aml.casemanagement.application.command;

import com.yourbank.aml.casemanagement.domain.model.CaseId;

public record CloseCaseCommand(CaseId caseId, String resolution) {}

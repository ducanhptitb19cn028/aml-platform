package com.yourbank.aml.casemanagement.application.command;

import com.yourbank.aml.casemanagement.domain.model.CaseId;

public record EscalateCaseCommand(CaseId caseId, String reason) {}

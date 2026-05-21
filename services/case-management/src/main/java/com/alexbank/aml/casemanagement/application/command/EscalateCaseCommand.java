package com.alexbank.aml.casemanagement.application.command;

import com.alexbank.aml.casemanagement.domain.model.CaseId;

public record EscalateCaseCommand(CaseId caseId, String reason) {}

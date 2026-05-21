package com.alexbank.aml.casemanagement.application.command;

import com.alexbank.aml.casemanagement.domain.model.CaseId;

public record CloseCaseCommand(CaseId caseId, String resolution) {}

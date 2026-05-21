package com.alexbank.aml.casemanagement.application.command;

import com.alexbank.aml.casemanagement.domain.model.CaseId;

public record AssignCaseCommand(CaseId caseId, String investigatorId) {}

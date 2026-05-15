package com.yourbank.aml.casemanagement.application.command;

import com.yourbank.aml.casemanagement.domain.model.CaseId;

public record AssignCaseCommand(CaseId caseId, String investigatorId) {}

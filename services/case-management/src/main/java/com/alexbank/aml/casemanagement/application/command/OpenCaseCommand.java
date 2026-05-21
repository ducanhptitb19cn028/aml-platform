package com.alexbank.aml.casemanagement.application.command;

public record OpenCaseCommand(String alertId, String customerId, int riskScore) {}

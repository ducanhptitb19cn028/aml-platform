package com.alexbank.aml.kyc.application.command;

import com.alexbank.aml.kyc.domain.model.CustomerId;

public record VerifyCustomerCommand(CustomerId customerId, String verifiedBy) {}

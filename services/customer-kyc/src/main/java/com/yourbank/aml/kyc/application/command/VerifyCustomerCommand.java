package com.yourbank.aml.kyc.application.command;

import com.yourbank.aml.kyc.domain.model.CustomerId;

public record VerifyCustomerCommand(CustomerId customerId, String verifiedBy) {}

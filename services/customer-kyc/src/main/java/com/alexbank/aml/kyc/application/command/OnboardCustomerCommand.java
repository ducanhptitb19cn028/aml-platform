package com.yourbank.aml.kyc.application.command;

import com.yourbank.aml.kyc.domain.model.CustomerId;

public record OnboardCustomerCommand(
        CustomerId customerId,
        String legalName,
        String residencyCountry
) {}

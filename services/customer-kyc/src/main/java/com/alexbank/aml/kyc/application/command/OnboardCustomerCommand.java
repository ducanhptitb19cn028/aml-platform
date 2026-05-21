package com.alexbank.aml.kyc.application.command;

import com.alexbank.aml.kyc.domain.model.CustomerId;

public record OnboardCustomerCommand(
        CustomerId customerId,
        String legalName,
        String residencyCountry
) {}

package com.yourbank.aml.kyc.application.command;

import com.yourbank.aml.kyc.domain.model.CustomerId;
import com.yourbank.aml.kyc.domain.model.RiskTier;

public record UpdateRiskProfileCommand(
        CustomerId customerId,
        RiskTier newTier,
        boolean politicallyExposed,
        boolean sanctioned,
        String reason
) {}

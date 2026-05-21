package com.alexbank.aml.kyc.application.command;

import com.alexbank.aml.kyc.domain.model.CustomerId;
import com.alexbank.aml.kyc.domain.model.RiskTier;

public record UpdateRiskProfileCommand(
        CustomerId customerId,
        RiskTier newTier,
        boolean politicallyExposed,
        boolean sanctioned,
        String reason
) {}

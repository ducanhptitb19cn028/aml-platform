package com.alexbank.aml.monitoring.application.command;

import com.alexbank.aml.monitoring.domain.model.Channel;
import com.alexbank.aml.monitoring.domain.model.CountryCode;
import com.alexbank.aml.monitoring.domain.model.Money;

import java.time.Instant;

public record EvaluateTransactionCommand(
        String customerId,
        Money amount,
        CountryCode originCountry,
        CountryCode destinationCountry,
        Channel channel,
        Instant occurredAt
) {}

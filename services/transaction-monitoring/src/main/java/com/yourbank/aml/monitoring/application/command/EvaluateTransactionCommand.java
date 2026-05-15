package com.yourbank.aml.monitoring.application.command;

import com.yourbank.aml.monitoring.domain.model.Channel;
import com.yourbank.aml.monitoring.domain.model.CountryCode;
import com.yourbank.aml.monitoring.domain.model.Money;

import java.time.Instant;

public record EvaluateTransactionCommand(
        String customerId,
        Money amount,
        CountryCode originCountry,
        CountryCode destinationCountry,
        Channel channel,
        Instant occurredAt
) {}

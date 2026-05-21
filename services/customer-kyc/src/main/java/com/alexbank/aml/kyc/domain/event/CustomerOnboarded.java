package com.alexbank.aml.kyc.domain.event;

import com.alexbank.aml.kyc.domain.model.CustomerId;

import java.time.Instant;
import java.util.UUID;

public record CustomerOnboarded(
        UUID eventId,
        Instant occurredAt,
        CustomerId customerId,
        String legalName,
        String residencyCountry
) implements DomainEvent {

    public static CustomerOnboarded now(CustomerId customerId, String legalName, String residencyCountry) {
        return new CustomerOnboarded(UUID.randomUUID(), Instant.now(),
                customerId, legalName, residencyCountry);
    }

    @Override public String aggregateId() { return customerId.asString(); }
    @Override public String eventType()   { return "customer.onboarded"; }
}

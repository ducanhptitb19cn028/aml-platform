package com.yourbank.aml.kyc.domain.event;

import com.yourbank.aml.kyc.domain.model.CustomerId;

import java.time.Instant;
import java.util.UUID;

public record CustomerVerified(
        UUID eventId,
        Instant occurredAt,
        CustomerId customerId,
        String verifiedBy
) implements DomainEvent {

    public static CustomerVerified now(CustomerId customerId, String verifiedBy) {
        return new CustomerVerified(UUID.randomUUID(), Instant.now(), customerId, verifiedBy);
    }

    @Override public String aggregateId() { return customerId.asString(); }
    @Override public String eventType()   { return "customer.verified"; }
}

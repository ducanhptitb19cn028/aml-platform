package com.alexbank.aml.kyc.domain.event;

import com.alexbank.aml.kyc.domain.model.CustomerId;

import java.time.Instant;
import java.util.UUID;

public record CustomerRejected(
        UUID eventId,
        Instant occurredAt,
        CustomerId customerId,
        String reason
) implements DomainEvent {

    public static CustomerRejected now(CustomerId customerId, String reason) {
        return new CustomerRejected(UUID.randomUUID(), Instant.now(), customerId, reason);
    }

    @Override public String aggregateId() { return customerId.asString(); }
    @Override public String eventType()   { return "customer.rejected"; }
}

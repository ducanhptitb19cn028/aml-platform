package com.yourbank.aml.kyc.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker interface for domain events in the KYC context.
 *
 * CustomerVerified and CustomerRiskUpdated are both consumed by other
 * services — those are the cross-context contracts. The other events
 * are internal to KYC.
 */
public sealed interface DomainEvent
        permits CustomerOnboarded, CustomerVerified, CustomerRejected, CustomerRiskUpdated {

    UUID eventId();
    Instant occurredAt();
    String aggregateId();
    String eventType();
}

package com.alexbank.aml.monitoring.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker interface for domain events in the Monitoring context.
 *
 * AlertRaised is the most important event in this service — it crosses
 * the bounded context boundary into Case Management. That makes its
 * schema a CONTRACT, locked down by Pact tests.
 */
public sealed interface DomainEvent
        permits AlertRaised {

    UUID eventId();
    Instant occurredAt();
    String aggregateId();
    String eventType();
}

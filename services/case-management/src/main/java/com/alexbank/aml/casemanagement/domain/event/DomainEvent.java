package com.alexbank.aml.casemanagement.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker interface for all domain events.
 *
 * RESEARCH NOTE — domain events are dual-purpose signals:
 *   1. Business: published to Kafka for other bounded contexts to react.
 *   2. Research: emitted as OpenTelemetry span events with business
 *      attributes, becoming high-fidelity behavioural features for
 *      the anomaly-detection model.
 *
 * Sealed so the publisher can exhaustively match on event type
 * without runtime instanceof chains.
 */
public sealed interface DomainEvent
        permits CaseOpened, CaseAssigned, CaseEscalated, CaseClosed {

    UUID eventId();
    Instant occurredAt();
    String aggregateId();
    String eventType();
}

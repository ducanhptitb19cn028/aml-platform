package com.yourbank.aml.monitoring.domain.event;

import com.yourbank.aml.monitoring.domain.model.AlertId;
import com.yourbank.aml.monitoring.domain.model.TransactionId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * THE inter-service contract event.
 *
 * Published to Kafka topic: aml.alerts.events
 * Consumed by:               Case Management (creates a Case from each AlertRaised)
 *
 * Schema stability is enforced by:
 *   - Pact contract test on the consumer side
 *   - Pact provider verification on this side
 *   - Schema versioning if a breaking change is needed (add v2 topic)
 *
 * Field changes that ARE safe:
 *   - Adding new optional fields (consumer ignores unknowns)
 *
 * Field changes that ARE NOT safe:
 *   - Renaming any field
 *   - Changing the type of any field
 *   - Removing any field
 *   - Tightening validation (rejecting payloads that used to be accepted)
 */
public record AlertRaised(
        UUID eventId,
        Instant occurredAt,
        AlertId alertId,
        TransactionId transactionId,
        String customerId,
        int riskScore,
        List<String> firedRuleIds,
        String rationale
) implements DomainEvent {

    public static AlertRaised now(AlertId alertId, TransactionId transactionId,
                                   String customerId, int riskScore,
                                   List<String> firedRuleIds, String rationale) {
        return new AlertRaised(UUID.randomUUID(), Instant.now(),
                alertId, transactionId, customerId, riskScore,
                List.copyOf(firedRuleIds), rationale);
    }

    @Override public String aggregateId() { return alertId.asString(); }
    @Override public String eventType()   { return "alert.raised"; }
}

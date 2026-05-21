package com.yourbank.aml.kyc.domain.event;

import com.yourbank.aml.kyc.domain.model.CustomerId;
import com.yourbank.aml.kyc.domain.model.RiskTier;

import java.time.Instant;
import java.util.UUID;

/**
 * THE second inter-service contract event in the platform.
 *
 * Published to: aml.customers.events
 * Consumed by:  Transaction Monitoring (rule recalibration)
 *               Case Management (case priority adjustment)
 *
 * Schema stability is enforced by Pact tests on both consumers.
 *
 * Note: this carries only the FIELDS THAT MATTER to consumers.
 * Internal KYC details (document references, audit trail of who
 * verified what when) are NOT in the public event — they belong to
 * KYC alone.
 */
public record CustomerRiskUpdated(
        UUID eventId,
        Instant occurredAt,
        CustomerId customerId,
        RiskTier previousTier,
        RiskTier newTier,
        boolean politicallyExposed,
        boolean sanctioned,
        String reason
) implements DomainEvent {

    public static CustomerRiskUpdated now(CustomerId customerId,
                                          RiskTier previousTier,
                                          RiskTier newTier,
                                          boolean politicallyExposed,
                                          boolean sanctioned,
                                          String reason) {
        return new CustomerRiskUpdated(UUID.randomUUID(), Instant.now(),
                customerId, previousTier, newTier,
                politicallyExposed, sanctioned, reason);
    }

    @Override public String aggregateId() { return customerId.asString(); }
    @Override public String eventType()   { return "customer.risk_updated"; }

    public boolean tierChanged() {
        return previousTier != newTier;
    }
}

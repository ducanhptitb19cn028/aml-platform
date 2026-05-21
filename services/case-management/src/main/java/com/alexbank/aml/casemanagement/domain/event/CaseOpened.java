package com.yourbank.aml.casemanagement.domain.event;

import com.yourbank.aml.casemanagement.domain.model.CaseId;
import java.time.Instant;
import java.util.UUID;

public record CaseOpened(
        UUID eventId,
        Instant occurredAt,
        CaseId caseId,
        String alertId,
        String customerId,
        int riskScore
) implements DomainEvent {

    public static CaseOpened now(CaseId caseId, String alertId, String customerId, int riskScore) {
        return new CaseOpened(UUID.randomUUID(), Instant.now(),
                caseId, alertId, customerId, riskScore);
    }

    @Override public String aggregateId() { return caseId.asString(); }
    @Override public String eventType()   { return "case.opened"; }
}

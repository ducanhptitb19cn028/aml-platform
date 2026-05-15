package com.yourbank.aml.casemanagement.domain.event;

import com.yourbank.aml.casemanagement.domain.model.CaseId;
import java.time.Instant;
import java.util.UUID;

public record CaseEscalated(
        UUID eventId,
        Instant occurredAt,
        CaseId caseId,
        String reason
) implements DomainEvent {

    public static CaseEscalated now(CaseId caseId, String reason) {
        return new CaseEscalated(UUID.randomUUID(), Instant.now(), caseId, reason);
    }

    @Override public String aggregateId() { return caseId.asString(); }
    @Override public String eventType()   { return "case.escalated"; }
}

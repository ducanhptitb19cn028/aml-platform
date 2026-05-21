package com.alexbank.aml.casemanagement.domain.event;

import com.alexbank.aml.casemanagement.domain.model.CaseId;
import java.time.Instant;
import java.util.UUID;

public record CaseAssigned(
        UUID eventId,
        Instant occurredAt,
        CaseId caseId,
        String investigatorId
) implements DomainEvent {

    public static CaseAssigned now(CaseId caseId, String investigatorId) {
        return new CaseAssigned(UUID.randomUUID(), Instant.now(), caseId, investigatorId);
    }

    @Override public String aggregateId() { return caseId.asString(); }
    @Override public String eventType()   { return "case.assigned"; }
}

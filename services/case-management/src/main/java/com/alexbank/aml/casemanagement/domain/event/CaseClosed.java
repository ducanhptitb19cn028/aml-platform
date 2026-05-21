package com.alexbank.aml.casemanagement.domain.event;

import com.alexbank.aml.casemanagement.domain.model.CaseId;
import java.time.Instant;
import java.util.UUID;

public record CaseClosed(
        UUID eventId,
        Instant occurredAt,
        CaseId caseId,
        String resolution
) implements DomainEvent {

    public static CaseClosed now(CaseId caseId, String resolution) {
        return new CaseClosed(UUID.randomUUID(), Instant.now(), caseId, resolution);
    }

    @Override public String aggregateId() { return caseId.asString(); }
    @Override public String eventType()   { return "case.closed"; }
}

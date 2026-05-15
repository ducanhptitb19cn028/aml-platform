package com.yourbank.aml.casemanagement.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly-typed identifier for a Case.
 * Records give us value-object semantics (equality by value, immutability)
 * for free. Crucially: NO Spring, NO JPA annotations here.
 */
public record CaseId(UUID value) {

    public CaseId {
        Objects.requireNonNull(value, "CaseId value cannot be null");
    }

    public static CaseId generate() {
        return new CaseId(UUID.randomUUID());
    }

    public static CaseId of(String raw) {
        return new CaseId(UUID.fromString(raw));
    }

    public String asString() {
        return value.toString();
    }
}

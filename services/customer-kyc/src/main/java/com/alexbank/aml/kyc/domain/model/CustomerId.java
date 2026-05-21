package com.alexbank.aml.kyc.domain.model;

import java.util.Objects;

/**
 * Customer identifier. Note this is NOT a UUID — banks use external
 * customer numbers issued at onboarding (often legacy mainframe
 * sequences). We treat the format as opaque and just enforce non-blank.
 *
 * This is a deliberate departure from the UUID-everywhere pattern in
 * the other services. The customerId we receive in upstream events
 * (Monitoring's transactions, Case Management's cases) is whatever
 * shape the source system produces — usually a short alphanumeric.
 */
public record CustomerId(String value) {

    public CustomerId {
        Objects.requireNonNull(value, "CustomerId value cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("CustomerId cannot be blank");
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException("CustomerId too long (max 64): " + value.length());
        }
    }

    public static CustomerId of(String raw) {
        return new CustomerId(raw);
    }

    public String asString() {
        return value;
    }
}

package com.yourbank.aml.monitoring.domain.model;

import java.util.Objects;
import java.util.UUID;

public record AlertId(UUID value) {
    public AlertId { Objects.requireNonNull(value, "AlertId value cannot be null"); }
    public static AlertId generate() { return new AlertId(UUID.randomUUID()); }
    public static AlertId of(String raw) { return new AlertId(UUID.fromString(raw)); }
    public String asString() { return value.toString(); }
}

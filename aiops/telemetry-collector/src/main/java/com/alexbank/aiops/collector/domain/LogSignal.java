package com.alexbank.aiops.collector.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogSignal(
        String service,
        Instant timestamp,
        String traceId,
        String spanId,
        String level,
        String message
) implements Signal {

    @Override
    public SignalType type() {
        return SignalType.LOG;
    }
}

package com.alexbank.aiops.stream.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Windowed feature record emitted to aiops.features for downstream ML scoring.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeatureRecord(
        String windowId,
        String service,
        Instant windowStart,
        Instant windowEnd,
        double avgValue,
        double maxValue,
        double rateOfChange,
        String metricName,
        int sampleCount
) {
}

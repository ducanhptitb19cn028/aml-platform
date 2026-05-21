package com.yourbank.aiops.stream.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * DTO mirroring telemetry-collector's MetricSignal for deserialization from aiops.telemetry.metrics.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MetricSignal(
        String service,
        Instant timestamp,
        String metricName,
        double value,
        Map<String, String> labels
) {
}

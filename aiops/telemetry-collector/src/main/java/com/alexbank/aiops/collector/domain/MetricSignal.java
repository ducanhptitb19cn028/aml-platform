package com.alexbank.aiops.collector.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MetricSignal(
        String service,
        Instant timestamp,
        String metricName,
        double value,
        Map<String, String> labels
) implements Signal {

    @Override
    public SignalType type() {
        return SignalType.METRIC;
    }
}

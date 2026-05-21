package com.alexbank.aiops.collector.domain;

import java.time.Instant;

public sealed interface Signal permits MetricSignal, TraceSignal, LogSignal {

    enum SignalType {
        METRIC, TRACE, LOG
    }

    String service();

    Instant timestamp();

    SignalType type();
}

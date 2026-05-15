package com.yourbank.aiops.collector.infrastructure.kafka;

import com.yourbank.aiops.collector.domain.LogSignal;
import com.yourbank.aiops.collector.domain.MetricSignal;
import com.yourbank.aiops.collector.domain.TraceSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SignalPublisher {

    private static final String TOPIC_METRICS = "aiops.telemetry.metrics";
    private static final String TOPIC_TRACES  = "aiops.telemetry.traces";
    private static final String TOPIC_LOGS    = "aiops.telemetry.logs";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishMetric(MetricSignal signal) {
        kafkaTemplate.send(TOPIC_METRICS, signal.service(), signal)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish MetricSignal for service={} metric={}: {}",
                                signal.service(), signal.metricName(), ex.getMessage());
                    } else {
                        log.debug("Published MetricSignal service={} metric={} value={}",
                                signal.service(), signal.metricName(), signal.value());
                    }
                });
    }

    public void publishTrace(TraceSignal signal) {
        kafkaTemplate.send(TOPIC_TRACES, signal.service(), signal)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish TraceSignal for service={} traceId={}: {}",
                                signal.service(), signal.traceId(), ex.getMessage());
                    } else {
                        log.debug("Published TraceSignal service={} traceId={}", signal.service(), signal.traceId());
                    }
                });
    }

    public void publishLog(LogSignal signal) {
        kafkaTemplate.send(TOPIC_LOGS, signal.service(), signal)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish LogSignal for service={}: {}", signal.service(), ex.getMessage());
                    } else {
                        log.debug("Published LogSignal service={} level={}", signal.service(), signal.level());
                    }
                });
    }
}

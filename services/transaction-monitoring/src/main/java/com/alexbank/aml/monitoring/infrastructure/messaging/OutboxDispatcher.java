package com.alexbank.aml.monitoring.infrastructure.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Scheduled worker that polls the outbox and forwards events to Kafka.
 *
 * Runs on every node — multiple workers are safe because the SQL query
 * uses FOR UPDATE SKIP LOCKED. Different workers pick up different
 * rows; same row never goes to two workers concurrently.
 *
 * Failure handling:
 *   - Kafka send fails → row stays pending → retried next poll
 *   - Worker crashes mid-send → row stays pending → retried by next worker
 *   - Send succeeds but mark-dispatched fails → at-least-once delivery
 *     → consumer dedups via processed_events
 *
 * The poll interval (default 500ms) trades latency for DB load. Tune
 * via aml.outbox.poll-interval-ms.
 */
@Component
class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxJpaRepository outboxRepo;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final String alertsTopic;
    private final int batchSize;
    private final long sendTimeoutSeconds;

    private final Counter dispatchedCounter;
    private final Counter failedCounter;

    OutboxDispatcher(OutboxJpaRepository outboxRepo,
                     KafkaTemplate<String, String> kafka,
                     Clock clock,
                     MeterRegistry meterRegistry,
                     @Value("${aml.kafka.topic.alerts}") String alertsTopic,
                     @Value("${aml.outbox.batch-size:50}") int batchSize,
                     @Value("${aml.outbox.send-timeout-seconds:5}") long sendTimeoutSeconds) {
        this.outboxRepo = outboxRepo;
        this.kafka = kafka;
        this.clock = clock;
        this.alertsTopic = alertsTopic;
        this.batchSize = batchSize;
        this.sendTimeoutSeconds = sendTimeoutSeconds;

        this.dispatchedCounter = Counter.builder("aml.outbox.dispatched")
                .description("Outbox events successfully sent to Kafka")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("aml.outbox.failed")
                .description("Outbox dispatch failures (will be retried)")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${aml.outbox.poll-interval-ms:500}")
    @Transactional
    public void dispatch() {
        List<OutboxJpaEntity> pending = outboxRepo.findPendingForDispatch(batchSize);
        if (pending.isEmpty()) {
            return;
        }

        for (OutboxJpaEntity row : pending) {
            try {
                // Synchronous send so we know whether to mark dispatched.
                // The .get() blocks until ack from Kafka or the timeout.
                kafka.send(alertsTopic, row.getAggregateId(), row.getPayload())
                        .get(sendTimeoutSeconds, TimeUnit.SECONDS);
                row.markDispatched(clock.instant());
                outboxRepo.save(row);
                dispatchedCounter.increment();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                row.recordFailure("interrupted");
                outboxRepo.save(row);
                failedCounter.increment(
                        Tag.of("error.kind", "interrupted").getValue().length());
                log.warn("Dispatcher interrupted while sending event {}", row.getId());
                return;
            } catch (ExecutionException | TimeoutException ex) {
                row.recordFailure(ex.getMessage());
                outboxRepo.save(row);
                failedCounter.increment();
                log.warn("Failed to dispatch event {} (attempts={}): {}",
                        row.getId(), row.getAttempts(), ex.getMessage());
                // Don't throw — let other rows in the batch still try
            }
        }
    }
}

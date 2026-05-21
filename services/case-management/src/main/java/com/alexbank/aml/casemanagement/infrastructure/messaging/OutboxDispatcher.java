package com.alexbank.aml.casemanagement.infrastructure.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

@Component
class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxJpaRepository outboxRepo;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final String caseEventsTopic;
    private final int batchSize;
    private final long sendTimeoutSeconds;

    private final Counter dispatchedCounter;
    private final Counter failedCounter;

    OutboxDispatcher(OutboxJpaRepository outboxRepo,
                     KafkaTemplate<String, String> kafka,
                     Clock clock,
                     MeterRegistry meterRegistry,
                     @Value("${aml.kafka.topic.case-events}") String caseEventsTopic,
                     @Value("${aml.outbox.batch-size:50}") int batchSize,
                     @Value("${aml.outbox.send-timeout-seconds:5}") long sendTimeoutSeconds) {
        this.outboxRepo = outboxRepo;
        this.kafka = kafka;
        this.clock = clock;
        this.caseEventsTopic = caseEventsTopic;
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
        if (pending.isEmpty()) return;

        for (OutboxJpaEntity row : pending) {
            try {
                kafka.send(caseEventsTopic, row.getAggregateId(), row.getPayload())
                        .get(sendTimeoutSeconds, TimeUnit.SECONDS);
                row.markDispatched(clock.instant());
                outboxRepo.save(row);
                dispatchedCounter.increment();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                row.recordFailure("interrupted");
                outboxRepo.save(row);
                failedCounter.increment();
                log.warn("Dispatcher interrupted while sending event {}", row.getId());
                return;
            } catch (ExecutionException | TimeoutException ex) {
                row.recordFailure(ex.getMessage());
                outboxRepo.save(row);
                failedCounter.increment();
                log.warn("Failed to dispatch event {} (attempts={}): {}",
                        row.getId(), row.getAttempts(), ex.getMessage());
            }
        }
    }
}

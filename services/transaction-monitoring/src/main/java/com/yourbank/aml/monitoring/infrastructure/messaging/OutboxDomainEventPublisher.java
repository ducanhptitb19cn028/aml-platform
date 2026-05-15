package com.yourbank.aml.monitoring.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourbank.aml.monitoring.application.port.DomainEventPublisher;
import com.yourbank.aml.monitoring.domain.event.DomainEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Outbox-based publisher.
 *
 * Instead of sending to Kafka directly (and risking commit/send
 * inconsistency), this writes the event to a JPA-managed outbox table.
 * Because this method runs inside the same @Transactional method that
 * persists the aggregate, the outbox row and the aggregate either both
 * commit or both don't.
 *
 * The actual Kafka send happens later in OutboxDispatcher.
 */
@Component
class OutboxDomainEventPublisher implements DomainEventPublisher {

    private final OutboxJpaRepository outboxRepo;
    private final ObjectMapper mapper;
    private final Tracer tracer;
    private final Clock clock;

    OutboxDomainEventPublisher(OutboxJpaRepository outboxRepo,
                               ObjectMapper mapper,
                               Tracer tracer,
                               Clock clock) {
        this.outboxRepo = outboxRepo;
        this.mapper = mapper;
        this.tracer = tracer;
        this.clock = clock;
    }

    @Override
    public void publish(List<DomainEvent> events) {
        Span current = tracer.currentSpan();
        for (DomainEvent event : events) {
            String payload;
            try {
                payload = mapper.writeValueAsString(event);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "Failed to serialise domain event " + event.eventType(), e);
            }

            // Tag the trace BEFORE the outbox write so even if the row
            // doesn't get persisted (transaction rollback), we still see
            // intent. After commit, the dispatcher adds its own span.
            if (current != null) {
                current.event("outbox.enqueued." + event.eventType());
                current.tag("domain.event.id", event.eventId().toString());
                current.tag("domain.aggregate.id", event.aggregateId());
            }

            outboxRepo.save(new OutboxJpaEntity(
                    event.eventId(),
                    aggregateTypeOf(event),
                    event.aggregateId(),
                    event.eventType(),
                    payload,
                    clock.instant()
            ));
        }
    }

    private static String aggregateTypeOf(DomainEvent event) {
        return switch (event.eventType()) {
            case "alert.raised" -> "Alert";
            default -> "Unknown";
        };
    }
}

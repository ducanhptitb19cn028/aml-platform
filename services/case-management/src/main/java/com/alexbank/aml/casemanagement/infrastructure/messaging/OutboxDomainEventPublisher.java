package com.alexbank.aml.casemanagement.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alexbank.aml.casemanagement.application.port.DomainEventPublisher;
import com.alexbank.aml.casemanagement.domain.event.DomainEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Outbox-based publisher. Same pattern as the Monitoring service.
 *
 * Writes the event to a JPA-managed outbox row inside the same
 * transaction as the aggregate save. The OutboxDispatcher polls the
 * outbox and forwards to Kafka.
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

            if (current != null) {
                current.event("outbox.enqueued." + event.eventType());
                current.tag("domain.event.id", event.eventId().toString());
                current.tag("domain.aggregate.id", event.aggregateId());
            }

            outboxRepo.save(new OutboxJpaEntity(
                    event.eventId(),
                    "Case",
                    event.aggregateId(),
                    event.eventType(),
                    payload,
                    clock.instant()
            ));
        }
    }
}

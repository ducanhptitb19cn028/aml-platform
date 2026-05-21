package com.yourbank.aml.kyc.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourbank.aml.kyc.application.port.DomainEventPublisher;
import com.yourbank.aml.kyc.domain.event.DomainEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

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
                    "Customer",
                    event.aggregateId(),
                    event.eventType(),
                    payload,
                    clock.instant()
            ));
        }
    }
}

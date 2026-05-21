package com.alexbank.aml.casemanagement.application;

import com.alexbank.aml.casemanagement.application.command.AssignCaseCommand;
import com.alexbank.aml.casemanagement.application.command.CloseCaseCommand;
import com.alexbank.aml.casemanagement.application.command.EscalateCaseCommand;
import com.alexbank.aml.casemanagement.application.command.OpenCaseCommand;
import com.alexbank.aml.casemanagement.application.port.CaseRepository;
import com.alexbank.aml.casemanagement.application.port.DomainEventPublisher;
import com.alexbank.aml.casemanagement.domain.model.Case;
import com.alexbank.aml.casemanagement.domain.model.CaseId;
import com.alexbank.aml.casemanagement.domain.model.RiskScore;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service — the orchestration layer.
 *
 * THIS IS WHERE OBSERVABILITY LIVES. The domain stays pure; the application
 * layer is where we add tracing, metrics, and business-meaningful log lines.
 *
 * Each method:
 *   1. Loads the aggregate
 *   2. Invokes domain behaviour
 *   3. Persists state
 *   4. Publishes events (also as span events for the research stream)
 *   5. Records counters with low-cardinality business labels
 *
 * Trace context propagates to Kafka so downstream services'
 * reactions are causally linkable end-to-end. This is the foundation
 * of cross-service anomaly propagation studies.
 */
@Service
public class CaseApplicationService {

    private final CaseRepository repository;
    private final DomainEventPublisher eventPublisher;
    private final Tracer tracer;
    private final Counter casesOpenedCounter;
    private final Counter casesEscalatedCounter;
    private final Counter caseRejectedTransitionCounter;

    public CaseApplicationService(CaseRepository repository,
                                  DomainEventPublisher eventPublisher,
                                  Tracer tracer,
                                  MeterRegistry meterRegistry) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.tracer = tracer;

        this.casesOpenedCounter = Counter.builder("aml.cases.opened")
                .description("Cases opened")
                .register(meterRegistry);
        this.casesEscalatedCounter = Counter.builder("aml.cases.escalated")
                .description("Cases escalated for further review")
                .register(meterRegistry);
        this.caseRejectedTransitionCounter = Counter.builder("aml.cases.rejected_transition")
                .description("Domain invariants caught an illegal transition — " +
                             "a strong anomaly signal for the detection model")
                .register(meterRegistry);
    }

    @Transactional
    @Timed(value = "aml.case.open", description = "Time to open a new case")
    public CaseId openCase(OpenCaseCommand cmd) {
        Span span = tracer.nextSpan().name("case.open").start();
        try (var scope = tracer.withSpan(span)) {
            RiskScore score = new RiskScore(cmd.riskScore());

            span.tag("alert.id", cmd.alertId());
            span.tag("customer.id", cmd.customerId());
            span.tag("risk.score", String.valueOf(cmd.riskScore()));
            span.tag("risk.band", score.band().name());

            Case c = Case.open(cmd.alertId(), cmd.customerId(), score);
            repository.save(c);
            eventPublisher.publish(c.pullDomainEvents());

            casesOpenedCounter.increment();
            span.tag("case.id", c.id().asString());
            return c.id();
        } finally {
            span.end();
        }
    }

    @Transactional
    @Timed(value = "aml.case.assign")
    public void assignCase(AssignCaseCommand cmd) {
        Span span = tracer.nextSpan().name("case.assign").start();
        try (var scope = tracer.withSpan(span)) {
            span.tag("case.id", cmd.caseId().asString());
            span.tag("investigator.id", cmd.investigatorId());

            Case c = repository.findById(cmd.caseId())
                    .orElseThrow(() -> new CaseNotFoundException(cmd.caseId()));

            try {
                c.assignTo(cmd.investigatorId());
            } catch (RuntimeException illegal) {
                caseRejectedTransitionCounter.increment();
                span.tag("error", "true");
                span.tag("error.kind", illegal.getClass().getSimpleName());
                throw illegal;
            }

            repository.save(c);
            eventPublisher.publish(c.pullDomainEvents());
        } finally {
            span.end();
        }
    }

    @Transactional
    @Timed(value = "aml.case.escalate")
    public void escalateCase(EscalateCaseCommand cmd) {
        Span span = tracer.nextSpan().name("case.escalate").start();
        try (var scope = tracer.withSpan(span)) {
            span.tag("case.id", cmd.caseId().asString());
            span.tag("reason", cmd.reason());

            Case c = repository.findById(cmd.caseId())
                    .orElseThrow(() -> new CaseNotFoundException(cmd.caseId()));

            try {
                c.escalate(cmd.reason());
            } catch (RuntimeException illegal) {
                caseRejectedTransitionCounter.increment();
                span.tag("error", "true");
                span.tag("error.kind", illegal.getClass().getSimpleName());
                throw illegal;
            }

            repository.save(c);
            eventPublisher.publish(c.pullDomainEvents());
            casesEscalatedCounter.increment();
        } finally {
            span.end();
        }
    }

    @Transactional
    @Timed(value = "aml.case.close")
    public void closeCase(CloseCaseCommand cmd) {
        Span span = tracer.nextSpan().name("case.close").start();
        try (var scope = tracer.withSpan(span)) {
            span.tag("case.id", cmd.caseId().asString());
            span.tag("resolution", cmd.resolution());

            Case c = repository.findById(cmd.caseId())
                    .orElseThrow(() -> new CaseNotFoundException(cmd.caseId()));

            try {
                c.close(cmd.resolution());
            } catch (RuntimeException illegal) {
                caseRejectedTransitionCounter.increment();
                span.tag("error", "true");
                span.tag("error.kind", illegal.getClass().getSimpleName());
                throw illegal;
            }

            repository.save(c);
            eventPublisher.publish(c.pullDomainEvents());
        } finally {
            span.end();
        }
    }
}

package com.alexbank.aml.casemanagement.application;

import com.alexbank.aml.casemanagement.application.command.OpenCaseCommand;
import com.alexbank.aml.casemanagement.application.port.CaseRepository;
import com.alexbank.aml.casemanagement.application.port.DomainEventPublisher;
import com.alexbank.aml.casemanagement.domain.event.DomainEvent;
import com.alexbank.aml.casemanagement.domain.model.Case;
import com.alexbank.aml.casemanagement.domain.model.CaseId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * Application-layer test using in-memory fakes for the ports.
 * Verifies the orchestration: load → invoke domain → save → publish.
 */
class CaseApplicationServiceTest {

    private InMemoryCaseRepository repo;
    private CapturingPublisher publisher;
    private CaseApplicationService service;

    @BeforeEach
    void setup() {
        repo = new InMemoryCaseRepository();
        publisher = new CapturingPublisher();
        Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
        service = new CaseApplicationService(repo, publisher, tracer, new SimpleMeterRegistry());
    }

    @Test
    void openCase_persists_aggregate_and_publishes_event() {
        CaseId id = service.openCase(new OpenCaseCommand("alert-1", "cust-1", 75));

        assertThat(repo.findById(id)).isPresent();
        assertThat(publisher.captured).hasSize(1);
        assertThat(publisher.captured.get(0).eventType()).isEqualTo("case.opened");
    }

    static class InMemoryCaseRepository implements CaseRepository {
        private final Map<CaseId, Case> store = new HashMap<>();

        @Override public void save(Case c) { store.put(c.id(), c); }
        @Override public Optional<Case> findById(CaseId id) {
            return Optional.ofNullable(store.get(id));
        }
    }

    static class CapturingPublisher implements DomainEventPublisher {
        final List<DomainEvent> captured = new ArrayList<>();
        @Override public void publish(List<DomainEvent> events) { captured.addAll(events); }
    }
}

package com.yourbank.aml.casemanagement.infrastructure;

import com.yourbank.aml.casemanagement.application.CaseApplicationService;
import com.yourbank.aml.casemanagement.application.command.OpenCaseCommand;
import com.yourbank.aml.casemanagement.application.port.ProcessedEventStore;
import com.yourbank.aml.casemanagement.infrastructure.messaging.AlertRaisedListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit-tests the listener's idempotency behaviour without needing a real
 * Kafka or database — uses an in-memory ProcessedEventStore.
 *
 * NOTE: This test uses reflection to instantiate the package-private
 * listener since it lives in a different test package.
 */
class AlertRaisedListenerTest {

    private CaseApplicationService caseService;
    private InMemoryProcessedEventStore processedStore;
    private AlertRaisedListener listener;

    @BeforeEach
    void setup() throws Exception {
        caseService = mock(CaseApplicationService.class);
        processedStore = new InMemoryProcessedEventStore();
        // Instantiate the package-private listener via reflection
        Class<?> cls = Class.forName(
                "com.yourbank.aml.casemanagement.infrastructure.messaging.AlertRaisedListener");
        var ctor = cls.getDeclaredConstructor(
                CaseApplicationService.class,
                ProcessedEventStore.class,
                io.micrometer.core.instrument.MeterRegistry.class);
        ctor.setAccessible(true);
        listener = (AlertRaisedListener) ctor.newInstance(
                caseService, processedStore, new SimpleMeterRegistry());
    }

    @Test
    void opens_a_case_for_a_new_event() throws Exception {
        UUID eventId = UUID.randomUUID();
        invokeListener(envelope(eventId));

        verify(caseService, times(1)).openCase(any(OpenCaseCommand.class));
    }

    @Test
    void does_not_open_a_second_case_for_a_duplicate_event() throws Exception {
        UUID eventId = UUID.randomUUID();

        invokeListener(envelope(eventId));
        invokeListener(envelope(eventId));   // duplicate

        verify(caseService, times(1)).openCase(any(OpenCaseCommand.class));
    }

    @Test
    void distinct_events_are_each_processed() throws Exception {
        invokeListener(envelope(UUID.randomUUID()));
        invokeListener(envelope(UUID.randomUUID()));
        invokeListener(envelope(UUID.randomUUID()));

        verify(caseService, times(3)).openCase(any(OpenCaseCommand.class));
    }

    @Test
    void idempotency_is_per_processor_id() {
        UUID eventId = UUID.randomUUID();
        // Different processors should each see this event as "new"
        assertThat(processedStore.markIfNotProcessed(eventId, "processor-a")).isTrue();
        assertThat(processedStore.markIfNotProcessed(eventId, "processor-b")).isTrue();
        // But the same processor sees it as duplicate the second time
        assertThat(processedStore.markIfNotProcessed(eventId, "processor-a")).isFalse();
    }

    private void invokeListener(Map<String, Object> envelope) throws Exception {
        var method = listener.getClass().getDeclaredMethod("onAlertRaised", Map.class);
        method.setAccessible(true);
        method.invoke(listener, envelope);
    }

    private Map<String, Object> envelope(UUID eventId) {
        Map<String, Object> e = new HashMap<>();
        e.put("eventId", eventId.toString());
        e.put("alertId", Map.of("value", UUID.randomUUID().toString()));
        e.put("customerId", "cust-1");
        e.put("riskScore", 75);
        e.put("rationale", "test");
        return e;
    }

    /** Threadsafe in-memory implementation matching JdbcProcessedEventStore semantics. */
    static class InMemoryProcessedEventStore implements ProcessedEventStore {
        private final Set<String> seen = new HashSet<>();

        @Override
        public synchronized boolean markIfNotProcessed(UUID eventId, String processor) {
            return seen.add(eventId.toString() + "::" + processor);
        }

        @SuppressWarnings("unused")
        boolean wasSeen(UUID eventId) {
            return seen.stream().anyMatch(s -> s.startsWith(eventId.toString()));
        }
    }
}

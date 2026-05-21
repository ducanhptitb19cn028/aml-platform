package com.alexbank.aml.casemanagement.application.port;

import java.util.UUID;

/**
 * Outbound port for tracking which inbound events we've already
 * processed. Used to make Kafka listeners idempotent under
 * at-least-once delivery.
 *
 * Implementations MUST guarantee that markIfNotProcessed returns true
 * exactly once for a given (eventId, processor) pair, even under
 * concurrent calls. The standard implementation uses a unique
 * constraint with INSERT ... ON CONFLICT DO NOTHING.
 */
public interface ProcessedEventStore {

    /**
     * Atomically check whether the event is new and mark it as processed.
     *
     * @return true if this call marked the event (caller should proceed
     *         with processing); false if it was already processed.
     */
    boolean markIfNotProcessed(UUID eventId, String processor);
}

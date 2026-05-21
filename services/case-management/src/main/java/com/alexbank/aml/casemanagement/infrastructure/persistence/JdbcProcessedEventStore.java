package com.yourbank.aml.casemanagement.infrastructure.persistence;

import com.yourbank.aml.casemanagement.application.port.ProcessedEventStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * JDBC adapter for ProcessedEventStore.
 *
 * Uses INSERT ... ON CONFLICT DO NOTHING so the marker is set
 * atomically. The number of affected rows tells us whether the event
 * is new (1) or already processed (0).
 *
 * This adapter is intentionally JDBC, not JPA: we want a single SQL
 * round-trip with no Hibernate overhead, and we want behaviour to be
 * obvious to a database administrator reading the query log.
 */
@Component
class JdbcProcessedEventStore implements ProcessedEventStore {

    private static final String INSERT_SQL = """
            INSERT INTO processed_events (event_id, processor)
            VALUES (?, ?)
            ON CONFLICT (event_id, processor) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    JdbcProcessedEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markIfNotProcessed(UUID eventId, String processor) {
        int affected = jdbc.update(INSERT_SQL, eventId, processor);
        return affected == 1;
    }
}

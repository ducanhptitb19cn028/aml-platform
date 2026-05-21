package com.alexbank.aml.monitoring.infrastructure;

import com.alexbank.aml.monitoring.application.port.DomainEventPublisher;
import com.alexbank.aml.monitoring.domain.event.AlertRaised;
import com.alexbank.aml.monitoring.domain.model.AlertId;
import com.alexbank.aml.monitoring.domain.model.TransactionId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the outbox publisher writes a row in the same transaction as
 * the calling code, and that the row carries the right metadata for the
 * dispatcher to pick up.
 *
 * Does NOT exercise the actual Kafka send — that's the dispatcher's job
 * and is covered by a separate test that uses an embedded Kafka.
 */
@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transaction_monitoring")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        // Disable scheduling so the dispatcher doesn't fire during the test
        r.add("aml.outbox.poll-interval-ms", () -> "999999999");
    }

    @Autowired DomainEventPublisher publisher;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    void writes_outbox_row_with_correct_metadata() {
        AlertRaised event = AlertRaised.now(
                AlertId.generate(),
                TransactionId.generate(),
                "cust-42",
                75,
                List.of("AML-101"),
                "high value transaction"
        );

        publisher.publish(List.of(event));

        // Read back via plain SQL so we exercise the actual schema
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE id = ?",
                Integer.class,
                event.eventId()
        );
        assertThat(count).isEqualTo(1);

        String aggregateType = jdbc.queryForObject(
                "SELECT aggregate_type FROM outbox WHERE id = ?",
                String.class,
                event.eventId()
        );
        assertThat(aggregateType).isEqualTo("Alert");

        String eventType = jdbc.queryForObject(
                "SELECT event_type FROM outbox WHERE id = ?",
                String.class,
                event.eventId()
        );
        assertThat(eventType).isEqualTo("alert.raised");

        Boolean dispatchedNull = jdbc.queryForObject(
                "SELECT dispatched_at IS NULL FROM outbox WHERE id = ?",
                Boolean.class,
                event.eventId()
        );
        assertThat(dispatchedNull).isTrue();
    }

    @Test
    @Transactional
    void payload_is_valid_json_with_event_fields() {
        AlertRaised event = AlertRaised.now(
                AlertId.generate(),
                TransactionId.generate(),
                "cust-99",
                90,
                List.of("AML-303", "AML-404"),
                "structuring"
        );

        publisher.publish(List.of(event));

        // The payload is JSONB. Test that we can query into it with ->>.
        String customerId = jdbc.queryForObject(
                "SELECT payload ->> 'customerId' FROM outbox WHERE id = ?",
                String.class,
                event.eventId()
        );
        assertThat(customerId).isEqualTo("cust-99");

        Integer riskScore = jdbc.queryForObject(
                "SELECT (payload ->> 'riskScore')::int FROM outbox WHERE id = ?",
                Integer.class,
                event.eventId()
        );
        assertThat(riskScore).isEqualTo(90);
    }
}

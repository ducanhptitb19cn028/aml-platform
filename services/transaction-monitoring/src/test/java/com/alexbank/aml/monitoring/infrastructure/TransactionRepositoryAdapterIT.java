package com.alexbank.aml.monitoring.infrastructure;

import com.alexbank.aml.monitoring.application.port.AlertRepository;
import com.alexbank.aml.monitoring.application.port.TransactionRepository;
import com.alexbank.aml.monitoring.domain.model.Alert;
import com.alexbank.aml.monitoring.domain.model.Channel;
import com.alexbank.aml.monitoring.domain.model.CountryCode;
import com.alexbank.aml.monitoring.domain.model.Money;
import com.alexbank.aml.monitoring.domain.model.Transaction;
import com.alexbank.aml.monitoring.domain.model.TransactionId;
import com.alexbank.aml.monitoring.domain.rule.RuleVerdict;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that spins up a real Postgres in Docker via
 * Testcontainers and verifies the JPA adapters round-trip correctly.
 *
 * Mirrors what case-management does. Crucially exercises:
 *   - Money <-> (BigDecimal, currency) mapping
 *   - CountryCode <-> CHAR(2) mapping
 *   - Channel enum <-> VARCHAR mapping
 *   - The customer/window query that powers velocity & structuring rules
 */
@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionRepositoryAdapterIT {

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
    }

    @Autowired TransactionRepository transactionRepository;
    @Autowired AlertRepository alertRepository;

    @Test
    void persists_and_reloads_a_transaction() {
        Transaction original = new Transaction(
                TransactionId.generate(),
                "cust-1",
                Money.of("1500", "GBP"),
                new CountryCode("GB"),
                new CountryCode("US"),
                Channel.SWIFT,
                Instant.now().truncatedTo(ChronoUnit.MILLIS)
        );
        transactionRepository.save(original);

        Optional<Transaction> reloaded = transactionRepository.findById(original.id());
        assertThat(reloaded).isPresent();
        Transaction t = reloaded.get();
        assertThat(t.customerId()).isEqualTo("cust-1");
        assertThat(t.amount().amount().toPlainString()).isEqualTo("1500.0000");
        assertThat(t.amount().currency().getCurrencyCode()).isEqualTo("GBP");
        assertThat(t.originCountry().value()).isEqualTo("GB");
        assertThat(t.destinationCountry().value()).isEqualTo("US");
        assertThat(t.channel()).isEqualTo(Channel.SWIFT);
        assertThat(t.crossesBorder()).isTrue();
    }

    @Test
    void window_query_returns_only_recent_transactions_for_a_customer() {
        Instant now = Instant.now();
        // Outside the window
        transactionRepository.save(tx("cust-A", "100", now.minus(Duration.ofDays(10))));
        // Inside the window
        transactionRepository.save(tx("cust-A", "200", now.minus(Duration.ofMinutes(30))));
        transactionRepository.save(tx("cust-A", "300", now.minus(Duration.ofMinutes(5))));
        // Different customer — should not be returned
        transactionRepository.save(tx("cust-B", "999", now.minus(Duration.ofMinutes(2))));

        List<Transaction> recent = transactionRepository
                .findByCustomerWithin("cust-A", Duration.ofHours(1));

        assertThat(recent).hasSize(2);
        assertThat(recent).allMatch(t -> t.customerId().equals("cust-A"));
    }

    @Test
    void persists_and_reloads_an_alert() {
        Alert alert = Alert.raise(
                TransactionId.generate(),
                "cust-7",
                85,
                List.of(RuleVerdict.fired("AML-101", 60, "high value"),
                        RuleVerdict.fired("AML-404", 35, "high-risk corridor"))
        );
        alertRepository.save(alert);

        Optional<Alert> reloaded = alertRepository.findById(alert.id());
        assertThat(reloaded).isPresent();
        Alert a = reloaded.get();
        assertThat(a.customerId()).isEqualTo("cust-7");
        assertThat(a.riskScore()).isEqualTo(85);
        assertThat(a.firedRuleIds()).contains("AML-101", "AML-404");
        assertThat(a.rationale()).contains("high value", "high-risk corridor");
    }

    private static Transaction tx(String customer, String amount, Instant when) {
        return new Transaction(
                TransactionId.generate(),
                customer,
                Money.of(amount, "GBP"),
                new CountryCode("GB"),
                new CountryCode("GB"),
                Channel.FASTER_PAYMENTS,
                when.truncatedTo(ChronoUnit.MILLIS)
        );
    }
}

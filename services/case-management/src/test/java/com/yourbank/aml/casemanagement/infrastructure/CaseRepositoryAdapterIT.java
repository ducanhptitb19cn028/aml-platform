package com.yourbank.aml.casemanagement.infrastructure;

import com.yourbank.aml.casemanagement.application.port.CaseRepository;
import com.yourbank.aml.casemanagement.domain.model.Case;
import com.yourbank.aml.casemanagement.domain.model.CaseId;
import com.yourbank.aml.casemanagement.domain.model.CaseStatus;
import com.yourbank.aml.casemanagement.domain.model.RiskScore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spins up a real Postgres in Docker and verifies the JPA adapter
 * round-trips the aggregate correctly.
 */
@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CaseRepositoryAdapterIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("case_management")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired CaseRepository repository;

    @Test
    void persists_and_reloads_a_case() {
        Case original = Case.open("alert-1", "cust-1", new RiskScore(85));
        original.assignTo("inv-7");
        repository.save(original);

        Optional<Case> reloaded = repository.findById(original.id());

        assertThat(reloaded).isPresent();
        Case c = reloaded.get();
        assertThat(c.alertId()).isEqualTo("alert-1");
        assertThat(c.customerId()).isEqualTo("cust-1");
        assertThat(c.riskScore().value()).isEqualTo(85);
        assertThat(c.status()).isEqualTo(CaseStatus.UNDER_INVESTIGATION);
        assertThat(c.assignedInvestigator()).contains("inv-7");
    }
}

package com.yourbank.aml.kyc.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.yourbank.aml.kyc.application.CustomerView;
import com.yourbank.aml.kyc.domain.model.RiskTier;
import com.yourbank.aml.kyc.domain.model.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;

import com.yourbank.aml.kyc.application.CustomerApplicationService;
import com.yourbank.aml.kyc.domain.model.CustomerId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Provider-side Pact verification for the SYNCHRONOUS HTTP contract.
 *
 * Two consumers (Transaction Monitoring and Case Management) declare
 * their expectations of GET /customers/{id} via @Pact tests on their
 * side. The generated pact files land in target/pacts/. This provider
 * test loads them and replays each interaction against the running
 * service.
 *
 * The state setup methods (@State) seed mock data so the provider
 * returns the shape the consumer asked for. We mock at the application
 * service level rather than the database — Pact verifies the wire
 * contract, not the persistence layer.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.bootstrap-servers=localhost:9999"
)
@Provider("customer-kyc")
@PactFolder("target/pacts")
class CustomerLookupProviderPactTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("customer_kyc")
            .withUsername("kyc")
            .withPassword("dev");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;

    @MockBean CustomerApplicationService customerService;

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verify(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @BeforeEach
    void setUp(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @State("a verified standard-tier customer exists with id cust-1")
    void verifiedStandardCustomer() {
        when(customerService.findById(any(CustomerId.class)))
                .thenReturn(Optional.of(new CustomerView(
                        "cust-1",
                        "Alice Smith",
                        VerificationStatus.VERIFIED,
                        RiskTier.STANDARD,
                        false,
                        false,
                        "GB",
                        Instant.parse("2026-01-15T10:00:00Z")
                )));
    }

    @State("a high-risk PEP customer exists with id cust-pep")
    void highRiskPepCustomer() {
        when(customerService.findById(any(CustomerId.class)))
                .thenReturn(Optional.of(new CustomerView(
                        "cust-pep",
                        "Diana Politico",
                        VerificationStatus.VERIFIED,
                        RiskTier.HIGH,
                        true,
                        false,
                        "GB",
                        Instant.parse("2026-02-01T10:00:00Z")
                )));
    }

    @State("no customer exists with id cust-missing")
    void missingCustomer() {
        when(customerService.findById(any(CustomerId.class)))
                .thenReturn(Optional.empty());
    }
}

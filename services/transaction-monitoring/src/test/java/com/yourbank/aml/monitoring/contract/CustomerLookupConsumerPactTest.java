package com.yourbank.aml.monitoring.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.yourbank.aml.monitoring.infrastructure.api.CustomerProfile;
import com.yourbank.aml.monitoring.infrastructure.api.KycClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer-side Pact test for the SYNCHRONOUS HTTP contract with
 * customer-kyc.
 *
 * Each @Pact method declares what we expect from the provider in a
 * given state. The framework spins up a mock HTTP server matching
 * those expectations; we point our KycClient at it and verify the
 * client correctly parses the response.
 *
 * The generated pact file lands in target/pacts/ and is consumed by
 * the customer-kyc provider test, which replays each interaction
 * against the real running service.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "customer-kyc", pactVersion = PactSpecVersion.V3)
class CustomerLookupConsumerPactTest {

    @Pact(consumer = "transaction-monitoring")
    public RequestResponsePact verifiedStandardCustomer(PactDslWithProvider builder) {
        return builder
                .given("a verified standard-tier customer exists with id cust-1")
                .uponReceiving("a request for customer cust-1")
                .path("/api/v1/customers/cust-1")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(java.util.Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .stringType("customerId", "cust-1")
                        .stringType("legalName", "Alice Smith")
                        .stringType("status", "VERIFIED")
                        .stringType("tier", "STANDARD")
                        .booleanType("politicallyExposed", false)
                        .booleanType("sanctioned", false)
                        .stringType("residencyCountry", "GB")
                        .datetime("lastUpdatedAt", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))
                .toPact();
    }

    @Pact(consumer = "transaction-monitoring")
    public RequestResponsePact highRiskPepCustomer(PactDslWithProvider builder) {
        return builder
                .given("a high-risk PEP customer exists with id cust-pep")
                .uponReceiving("a request for customer cust-pep")
                .path("/api/v1/customers/cust-pep")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(java.util.Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .stringType("customerId", "cust-pep")
                        .stringType("legalName", "Diana Politico")
                        .stringType("status", "VERIFIED")
                        .stringType("tier", "HIGH")
                        .booleanType("politicallyExposed", true)
                        .booleanType("sanctioned", false)
                        .stringType("residencyCountry", "GB")
                        .datetime("lastUpdatedAt", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))
                .toPact();
    }

    @Pact(consumer = "transaction-monitoring")
    public RequestResponsePact missingCustomer(PactDslWithProvider builder) {
        return builder
                .given("no customer exists with id cust-missing")
                .uponReceiving("a request for customer cust-missing")
                .path("/api/v1/customers/cust-missing")
                .method("GET")
                .willRespondWith()
                .status(404)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "verifiedStandardCustomer")
    void parses_standard_customer(MockServer mockServer) {
        KycClient client = new KycClient(mockServer.getUrl());

        Optional<CustomerProfile> profile = client.findById("cust-1");

        assertThat(profile).isPresent();
        assertThat(profile.get().tier()).isEqualTo("STANDARD");
        assertThat(profile.get().politicallyExposed()).isFalse();
        assertThat(profile.get().isHighRisk()).isFalse();
    }

    @Test
    @PactTestFor(pactMethod = "highRiskPepCustomer")
    void parses_high_risk_pep_customer(MockServer mockServer) {
        KycClient client = new KycClient(mockServer.getUrl());

        Optional<CustomerProfile> profile = client.findById("cust-pep");

        assertThat(profile).isPresent();
        assertThat(profile.get().tier()).isEqualTo("HIGH");
        assertThat(profile.get().politicallyExposed()).isTrue();
        assertThat(profile.get().isHighRisk()).isTrue();
    }

    @Test
    @PactTestFor(pactMethod = "missingCustomer")
    void returns_empty_for_missing_customer(MockServer mockServer) {
        KycClient client = new KycClient(mockServer.getUrl());

        Optional<CustomerProfile> profile = client.findById("cust-missing");

        assertThat(profile).isEmpty();
    }
}

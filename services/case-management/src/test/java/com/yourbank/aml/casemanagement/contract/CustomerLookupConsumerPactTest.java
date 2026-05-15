package com.yourbank.aml.casemanagement.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.yourbank.aml.casemanagement.infrastructure.api.CustomerProfile;
import com.yourbank.aml.casemanagement.infrastructure.api.KycClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Case Management is the second consumer of the GET /customers/{id}
 * contract. It declares its own expectations independently — Pact
 * resolves them all when the customer-kyc provider verifies.
 *
 * Notice that this test only asks for the fields case-management uses
 * (legalName, tier, PEP, sanctioned). It is fine for monitoring's
 * test to ask for different fields. The provider satisfies BOTH
 * consumer expectations.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "customer-kyc", pactVersion = PactSpecVersion.V3)
class CustomerLookupConsumerPactTest {

    @Pact(consumer = "case-management")
    public RequestResponsePact verifiedCustomer(PactDslWithProvider builder) {
        return builder
                .given("a verified standard-tier customer exists with id cust-1")
                .uponReceiving("a request for customer cust-1 (case-mgmt)")
                .path("/api/v1/customers/cust-1")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(java.util.Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .stringType("customerId", "cust-1")
                        .stringType("legalName", "Alice Smith")
                        .stringType("tier", "STANDARD")
                        .booleanType("politicallyExposed", false)
                        .booleanType("sanctioned", false)
                        .stringType("residencyCountry", "GB"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "verifiedCustomer")
    void parses_customer_for_investigator_view(MockServer mockServer) {
        KycClient client = new KycClient(mockServer.getUrl());

        Optional<CustomerProfile> profile = client.findById("cust-1");

        assertThat(profile).isPresent();
        assertThat(profile.get().legalName()).isEqualTo("Alice Smith");
        assertThat(profile.get().tier()).isEqualTo("STANDARD");
    }
}

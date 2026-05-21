package com.alexbank.aml.monitoring.infrastructure.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * HTTP client for the Customer / KYC service.
 *
 * Used during transaction evaluation to look up the customer's risk
 * tier and PEP/sanctions flags. The KYC service's response shape is
 * locked by Pact — see the consumer test in
 * src/test/java/.../contract/CustomerLookupConsumerPactTest.java.
 *
 * We accept that this is a synchronous dependency on the hot path.
 * The KYC service is a master-data service; it's slow-changing,
 * cacheable, and high-availability. If it's unreachable, we degrade
 * to a default profile rather than failing the transaction evaluation
 * entirely (returns Optional.empty(), and the rule engine treats the
 * missing profile as STANDARD tier with no PEP/sanctions flags).
 */
@Component
public class KycClient {

    private final RestClient restClient;

    public KycClient(@Value("${aml.kyc.base-url:http://localhost:8082}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public Optional<CustomerProfile> findById(String customerId) {
        try {
            CustomerProfile profile = restClient.get()
                    .uri("/api/v1/customers/{id}", customerId)
                    .retrieve()
                    .body(CustomerProfile.class);
            return Optional.ofNullable(profile);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}

package com.alexbank.aml.casemanagement.infrastructure.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * HTTP client to the Customer / KYC service.
 *
 * Used when an investigator opens or works a case — we want to surface
 * the customer's legal name and risk indicators alongside the case
 * record.
 *
 * Contract is locked by Pact — see CustomerLookupConsumerPactTest.
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
            return Optional.ofNullable(restClient.get()
                    .uri("/api/v1/customers/{id}", customerId)
                    .retrieve()
                    .body(CustomerProfile.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}

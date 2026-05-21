package com.alexbank.aml.casemanagement.infrastructure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Slim view of a KYC customer used by Case Management when an
 * investigator opens a case.
 *
 * Different from Monitoring's CustomerProfile because Case Management
 * has different needs — it surfaces the legal name to investigators,
 * it doesn't care about the residency country directly. We don't
 * share types across bounded contexts even when the underlying
 * concept is the same.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerProfile(
        String customerId,
        String legalName,
        String tier,
        boolean politicallyExposed,
        boolean sanctioned,
        String residencyCountry
) {}

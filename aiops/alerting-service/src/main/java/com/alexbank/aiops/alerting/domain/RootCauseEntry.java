package com.alexbank.aiops.alerting.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RootCauseEntry(
        String component,
        double weight
) {
}

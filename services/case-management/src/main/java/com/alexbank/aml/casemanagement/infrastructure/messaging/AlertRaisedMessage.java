package com.yourbank.aml.casemanagement.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;
import java.util.UUID;

/**
 * DTO for the inbound AlertRaised event from Transaction Monitoring.
 *
 * Intentionally a separate type from any class in the producer's
 * codebase. We do not share types across bounded contexts — that would
 * couple the consumer to the producer's internal model. The contract
 * is the message shape, locked by Pact, not a shared library.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) lets the producer add
 * new optional fields without breaking us.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record AlertRaisedMessage(
        UUID eventId,
        String alertId,
        String customerId,
        int riskScore,
        String rationale
) {

    /**
     * The producer wraps alertId as {"value": "uuid-string"}. We accept
     * either shape — the wrapped object form (current) or a flat string
     * (a future schema evolution) — by post-processing here.
     */
    static AlertRaisedMessage fromEnvelope(Map<String, Object> envelope) {
        UUID eventId = UUID.fromString((String) envelope.get("eventId"));
        String alertId = extractId(envelope.get("alertId"));
        String customerId = (String) envelope.get("customerId");
        int riskScore = ((Number) envelope.get("riskScore")).intValue();
        String rationale = (String) envelope.get("rationale");
        return new AlertRaisedMessage(eventId, alertId, customerId, riskScore, rationale);
    }

    @SuppressWarnings("unchecked")
    private static String extractId(Object idField) {
        if (idField == null) return null;
        if (idField instanceof String s) return s;
        if (idField instanceof Map<?, ?> m) {
            Object v = ((Map<String, Object>) m).get("value");
            return v != null ? v.toString() : null;
        }
        return idField.toString();
    }
}

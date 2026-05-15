package com.yourbank.aml.monitoring.contract;

import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.junitsupport.target.TestTarget;
import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourbank.aml.monitoring.domain.event.AlertRaised;
import com.yourbank.aml.monitoring.domain.model.AlertId;
import com.yourbank.aml.monitoring.domain.model.TransactionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Producer-side Pact verification.
 *
 * Loads the pact file produced by case-management's consumer test
 * and verifies that the AlertRaised events we actually emit match
 * the consumer's expected shape.
 *
 * In CI, the pact file would come from a Pact Broker. For local
 * development we read it from the consumer's target/pacts directory.
 */
@Provider("transaction-monitoring")
@PactFolder("../case-management/target/pacts")
class AlertRaisedProducerPactTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verify(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @BeforeEach
    void setUp(PactVerificationContext context) {
        context.setTarget(new MessageTestTarget());
    }

    @State("a transaction triggers a high-value rule")
    void highValueRuleState() {
        // Test fixture state — no DB needed; producer just needs to
        // produce the same shape as in the @PactVerifyProvider method.
    }

    @PactVerifyProvider("AlertRaised event")
    String produceAlertRaised() throws Exception {
        AlertRaised event = AlertRaised.now(
                AlertId.generate(),
                TransactionId.generate(),
                "cust-1",
                75,
                List.of("AML-101"),
                "amount exceeds threshold"
        );

        // Serialise to the JSON shape we publish to Kafka
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", event.eventId().toString());
        payload.put("occurredAt", event.occurredAt().toString());
        payload.put("alertId", Map.of("value", event.alertId().asString()));
        payload.put("transactionId", Map.of("value", event.transactionId().asString()));
        payload.put("customerId", event.customerId());
        payload.put("riskScore", event.riskScore());
        payload.put("firedRuleIds", event.firedRuleIds());
        payload.put("rationale", event.rationale());
        payload.put("eventType", event.eventType());
        payload.put("aggregateId", event.aggregateId());

        return MAPPER.writeValueAsString(payload);
    }
}

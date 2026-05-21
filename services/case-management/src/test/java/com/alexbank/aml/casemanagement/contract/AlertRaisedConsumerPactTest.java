package com.alexbank.aml.casemanagement.contract;

import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.messaging.MessagePact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer-side contract test.
 *
 * Case Management is the CONSUMER of the AlertRaised event published by
 * Transaction Monitoring. This test asserts what we EXPECT the producer
 * to send. The generated pact file is shared with the provider, which
 * verifies it can produce exactly this shape.
 *
 * If a future change in Transaction Monitoring breaks this contract,
 * the provider's CI fails — long before the change reaches integration.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "transaction-monitoring", providerType = ProviderType.ASYNCH, pactVersion = PactSpecVersion.V3)
class AlertRaisedConsumerPactTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Pact(consumer = "case-management")
    public MessagePact alertRaisedEvent(MessagePactBuilder builder) {
        PactDslJsonBody body = new PactDslJsonBody()
                .uuid("eventId")
                .datetime("occurredAt", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                .object("alertId")
                    .uuid("value")
                .closeObject().asBody()
                .object("transactionId")
                    .uuid("value")
                .closeObject().asBody()
                .stringType("customerId", "cust-1")
                .integerType("riskScore", 75)
                .array("firedRuleIds")
                    .stringType("AML-101")
                .closeArray().asBody()
                .stringType("rationale", "amount exceeds threshold")
                .stringType("eventType", "alert.raised")
                .stringType("aggregateId");

        return builder
                .given("a transaction triggers a high-value rule")
                .expectsToReceive("AlertRaised event")
                .withMetadata(Map.of("contentType", "application/json"))
                .withContent(body)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "alertRaisedEvent")
    void can_consume_alert_raised(MessagePact pact) throws Exception {
        // Verify the pact body is parseable as the consumer expects
        byte[] bytes = pact.getMessages().get(0).contentsAsBytes();
        JsonNode json = MAPPER.readTree(bytes);

        assertThat(json.get("eventType").asText()).isEqualTo("alert.raised");
        assertThat(json.get("riskScore").asInt()).isBetween(0, 100);
        assertThat(json.get("customerId").asText()).isNotBlank();
        assertThat(json.get("firedRuleIds").isArray()).isTrue();
        assertThat(json.get("rationale").asText()).isNotBlank();
    }
}

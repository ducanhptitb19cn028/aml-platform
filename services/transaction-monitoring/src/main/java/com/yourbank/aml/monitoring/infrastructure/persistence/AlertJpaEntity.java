package com.yourbank.aml.monitoring.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alerts")
public class AlertJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    /** Comma-separated rule IDs. Cardinality is small (<10) so a normalised
     *  table would be over-engineering at this stage. */
    @Column(name = "fired_rule_ids", nullable = false, length = 512)
    private String firedRuleIds;

    @Column(name = "rationale", nullable = false, length = 4000)
    private String rationale;

    @Column(name = "raised_at", nullable = false)
    private Instant raisedAt;

    protected AlertJpaEntity() {}

    public AlertJpaEntity(UUID id, UUID transactionId, String customerId,
                          int riskScore, String firedRuleIds, String rationale,
                          Instant raisedAt) {
        this.id = id;
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.riskScore = riskScore;
        this.firedRuleIds = firedRuleIds;
        this.rationale = rationale;
        this.raisedAt = raisedAt;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public String getCustomerId() { return customerId; }
    public int getRiskScore() { return riskScore; }
    public String getFiredRuleIds() { return firedRuleIds; }
    public String getRationale() { return rationale; }
    public Instant getRaisedAt() { return raisedAt; }
}

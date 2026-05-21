package com.yourbank.aml.casemanagement.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

import com.yourbank.aml.casemanagement.domain.model.CaseStatus;

/**
 * JPA persistence model. SEPARATE from the domain Case class.
 *
 * Why two classes? So that:
 *   - Domain stays free of JPA annotations (testable without DB)
 *   - Persistence concerns (versioning, column types) don't leak into domain
 *   - You can change the storage strategy (e.g. event sourcing) without
 *     touching the domain model
 *
 * The CaseRepositoryAdapter handles mapping in both directions.
 */
@Entity
@Table(name = "cases")
public class CaseJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "alert_id", nullable = false, length = 64)
    private String alertId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CaseStatus status;

    @Column(name = "assigned_investigator", length = 64)
    private String assignedInvestigator;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CaseJpaEntity() {} // JPA

    public CaseJpaEntity(UUID id, String alertId, String customerId, int riskScore,
                         CaseStatus status, String assignedInvestigator,
                         Instant openedAt, Instant lastUpdatedAt) {
        this.id = id;
        this.alertId = alertId;
        this.customerId = customerId;
        this.riskScore = riskScore;
        this.status = status;
        this.assignedInvestigator = assignedInvestigator;
        this.openedAt = openedAt;
        this.lastUpdatedAt = lastUpdatedAt;
    }

    // Getters used by the adapter — no setters; updates go via constructor/merge
    public UUID getId() { return id; }
    public String getAlertId() { return alertId; }
    public String getCustomerId() { return customerId; }
    public int getRiskScore() { return riskScore; }
    public CaseStatus getStatus() { return status; }
    public String getAssignedInvestigator() { return assignedInvestigator; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public long getVersion() { return version; }

    /** Used by the adapter to update mutable fields during an upsert. */
    void apply(CaseStatus status, String assignedInvestigator, Instant lastUpdatedAt) {
        this.status = status;
        this.assignedInvestigator = assignedInvestigator;
        this.lastUpdatedAt = lastUpdatedAt;
    }
}

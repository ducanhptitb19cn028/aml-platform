package com.yourbank.aml.casemanagement.infrastructure.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox")
class OutboxJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    protected OutboxJpaEntity() {}

    OutboxJpaEntity(UUID id, String aggregateType, String aggregateId,
                    String eventType, String payload, Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    UUID getId() { return id; }
    String getAggregateId() { return aggregateId; }
    String getEventType() { return eventType; }
    String getPayload() { return payload; }
    Instant getDispatchedAt() { return dispatchedAt; }
    int getAttempts() { return attempts; }

    void markDispatched(Instant when) { this.dispatchedAt = when; }

    void recordFailure(String error) {
        this.attempts++;
        this.lastError = error.length() > 2000 ? error.substring(0, 2000) : error;
    }
}

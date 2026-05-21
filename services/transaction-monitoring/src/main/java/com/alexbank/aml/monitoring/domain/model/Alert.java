package com.alexbank.aml.monitoring.domain.model;

import com.alexbank.aml.monitoring.domain.event.AlertRaised;
import com.alexbank.aml.monitoring.domain.event.DomainEvent;
import com.alexbank.aml.monitoring.domain.rule.RuleVerdict;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Alert is the second aggregate in this context (alongside Transaction).
 *
 * An Alert is raised when the rule engine determines a transaction is
 * suspicious. Once raised, alerts are immutable in the Monitoring context
 * — their lifecycle is owned by the Case Management context. This is
 * the bounded-context boundary in action: each context owns the parts
 * of the lifecycle relevant to its responsibility.
 */
public final class Alert {

    private final AlertId id;
    private final TransactionId transactionId;
    private final String customerId;
    private final int riskScore;
    private final List<String> firedRuleIds;
    private final String rationale;
    private final Instant raisedAt;
    private final List<DomainEvent> uncommittedEvents = new ArrayList<>();

    /** Reconstitute from persistence — does not raise events. */
    public Alert(AlertId id, TransactionId transactionId, String customerId,
                 int riskScore, List<String> firedRuleIds, String rationale,
                 Instant raisedAt) {
        this.id = Objects.requireNonNull(id);
        this.transactionId = Objects.requireNonNull(transactionId);
        this.customerId = Objects.requireNonNull(customerId);
        if (riskScore < 0 || riskScore > 100) {
            throw new IllegalArgumentException("riskScore must be 0..100");
        }
        this.riskScore = riskScore;
        this.firedRuleIds = List.copyOf(firedRuleIds);
        this.rationale = Objects.requireNonNull(rationale);
        this.raisedAt = Objects.requireNonNull(raisedAt);
    }

    /**
     * Factory: raise a new alert from a rule-engine evaluation.
     * Validates that at least one rule fired (otherwise the engine would
     * not have decided to alert).
     */
    public static Alert raise(TransactionId transactionId, String customerId,
                              int riskScore, List<RuleVerdict> firedVerdicts) {
        if (firedVerdicts == null || firedVerdicts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot raise an alert without at least one fired rule");
        }
        if (firedVerdicts.stream().anyMatch(v -> !v.fired())) {
            throw new IllegalArgumentException(
                    "firedVerdicts must contain only fired verdicts");
        }

        AlertId id = AlertId.generate();
        Instant now = Instant.now();

        List<String> firedIds = firedVerdicts.stream().map(RuleVerdict::ruleId).toList();
        String rationale = String.join("; ",
                firedVerdicts.stream().map(RuleVerdict::rationale).toList());

        Alert alert = new Alert(id, transactionId, customerId,
                riskScore, firedIds, rationale, now);
        alert.uncommittedEvents.add(AlertRaised.now(
                id, transactionId, customerId, riskScore, firedIds, rationale));
        return alert;
    }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> snapshot = List.copyOf(uncommittedEvents);
        uncommittedEvents.clear();
        return snapshot;
    }

    public List<DomainEvent> peekDomainEvents() {
        return Collections.unmodifiableList(uncommittedEvents);
    }

    public AlertId id()                      { return id; }
    public TransactionId transactionId()     { return transactionId; }
    public String customerId()               { return customerId; }
    public int riskScore()                   { return riskScore; }
    public List<String> firedRuleIds()       { return firedRuleIds; }
    public String rationale()                { return rationale; }
    public Instant raisedAt()                { return raisedAt; }
}

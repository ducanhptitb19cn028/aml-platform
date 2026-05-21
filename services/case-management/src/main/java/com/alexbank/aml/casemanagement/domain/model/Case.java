package com.yourbank.aml.casemanagement.domain.model;

import com.yourbank.aml.casemanagement.domain.event.CaseAssigned;
import com.yourbank.aml.casemanagement.domain.event.CaseClosed;
import com.yourbank.aml.casemanagement.domain.event.CaseEscalated;
import com.yourbank.aml.casemanagement.domain.event.CaseOpened;
import com.yourbank.aml.casemanagement.domain.event.DomainEvent;
import com.yourbank.aml.casemanagement.domain.exception.IllegalCaseTransitionException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Case aggregate root.
 *
 * Design contract:
 *   - State changes happen ONLY through methods that enforce invariants.
 *   - All state changes record a domain event.
 *   - The aggregate has no Spring, no JPA, no infrastructure imports.
 *
 * That last point is enforced by ArchUnit and is what lets us run
 * thousands of domain tests in milliseconds.
 */
public class Case {

    private final CaseId id;
    private final String alertId;
    private final String customerId;
    private final RiskScore riskScore;
    private final Instant openedAt;

    private CaseStatus status;
    private String assignedInvestigator;
    private Instant lastUpdatedAt;
    private final List<DomainEvent> uncommittedEvents = new ArrayList<>();

    /**
     * Reconstitute from persistence. Does NOT raise events.
     * The repository adapter calls this; nothing else should.
     */
    public Case(CaseId id, String alertId, String customerId, RiskScore riskScore,
                CaseStatus status, String assignedInvestigator,
                Instant openedAt, Instant lastUpdatedAt) {
        this.id = Objects.requireNonNull(id);
        this.alertId = Objects.requireNonNull(alertId);
        this.customerId = Objects.requireNonNull(customerId);
        this.riskScore = Objects.requireNonNull(riskScore);
        this.status = Objects.requireNonNull(status);
        this.assignedInvestigator = assignedInvestigator;
        this.openedAt = Objects.requireNonNull(openedAt);
        this.lastUpdatedAt = Objects.requireNonNull(lastUpdatedAt);
    }

    /** Factory: open a new case from an alert. Raises CaseOpened. */
    public static Case open(String alertId, String customerId, RiskScore riskScore) {
        if (alertId == null || alertId.isBlank()) {
            throw new IllegalArgumentException("alertId is required");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }
        Objects.requireNonNull(riskScore, "riskScore is required");

        Instant now = Instant.now();
        CaseId newId = CaseId.generate();
        Case c = new Case(newId, alertId, customerId, riskScore,
                CaseStatus.OPEN, null, now, now);
        c.uncommittedEvents.add(
                CaseOpened.now(newId, alertId, customerId, riskScore.value()));
        return c;
    }

    public void assignTo(String investigatorId) {
        if (investigatorId == null || investigatorId.isBlank()) {
            throw new IllegalArgumentException("investigatorId is required");
        }
        if (status.isTerminal()) {
            throw new IllegalCaseTransitionException(status, status);
        }
        if (status == CaseStatus.OPEN) {
            transitionTo(CaseStatus.UNDER_INVESTIGATION);
        }
        this.assignedInvestigator = investigatorId;
        this.lastUpdatedAt = Instant.now();
        uncommittedEvents.add(CaseAssigned.now(id, investigatorId));
    }

    public void escalate(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("escalation reason is required");
        }
        transitionTo(CaseStatus.ESCALATED);
        uncommittedEvents.add(CaseEscalated.now(id, reason));
    }

    public void close(String resolution) {
        if (resolution == null || resolution.isBlank()) {
            throw new IllegalArgumentException("resolution is required");
        }
        transitionTo(CaseStatus.CLOSED);
        uncommittedEvents.add(CaseClosed.now(id, resolution));
    }

    private void transitionTo(CaseStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalCaseTransitionException(status, target);
        }
        this.status = target;
        this.lastUpdatedAt = Instant.now();
    }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> snapshot = List.copyOf(uncommittedEvents);
        uncommittedEvents.clear();
        return snapshot;
    }

    public List<DomainEvent> peekDomainEvents() {
        return Collections.unmodifiableList(uncommittedEvents);
    }

    // Accessors only — no setters. State changes only through behaviour methods.
    public CaseId id()                              { return id; }
    public String alertId()                         { return alertId; }
    public String customerId()                      { return customerId; }
    public RiskScore riskScore()                    { return riskScore; }
    public CaseStatus status()                      { return status; }
    public Optional<String> assignedInvestigator()  { return Optional.ofNullable(assignedInvestigator); }
    public Instant openedAt()                       { return openedAt; }
    public Instant lastUpdatedAt()                  { return lastUpdatedAt; }
}

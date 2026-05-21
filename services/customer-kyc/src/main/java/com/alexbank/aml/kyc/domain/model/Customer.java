package com.alexbank.aml.kyc.domain.model;

import com.alexbank.aml.kyc.domain.event.CustomerOnboarded;
import com.alexbank.aml.kyc.domain.event.CustomerRejected;
import com.alexbank.aml.kyc.domain.event.CustomerRiskUpdated;
import com.alexbank.aml.kyc.domain.event.CustomerVerified;
import com.alexbank.aml.kyc.domain.event.DomainEvent;
import com.alexbank.aml.kyc.domain.exception.IllegalVerificationTransitionException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Customer aggregate root.
 *
 * In the KYC context, the customer is the system of record. Other
 * services hold a copy of the customer's relevant facts (riskTier,
 * sanctioned flag), refreshed via CustomerRiskUpdated events.
 *
 * Invariants enforced here:
 *   - status transitions follow VerificationStatus.canTransitionTo
 *   - a sanctioned customer must be PROHIBITED tier (enforced in RiskProfile)
 *   - REJECTED is terminal
 *   - every state-changing method emits at least one domain event
 */
public class Customer {

    private final CustomerId id;
    private final String legalName;
    private final Instant onboardedAt;

    private VerificationStatus status;
    private RiskProfile riskProfile;
    private Instant lastUpdatedAt;
    private final List<DomainEvent> uncommittedEvents = new ArrayList<>();

    /** Reconstitute from persistence. Does NOT raise events. */
    public Customer(CustomerId id, String legalName,
                    VerificationStatus status, RiskProfile riskProfile,
                    Instant onboardedAt, Instant lastUpdatedAt) {
        this.id = Objects.requireNonNull(id);
        if (legalName == null || legalName.isBlank()) {
            throw new IllegalArgumentException("legalName is required");
        }
        this.legalName = legalName;
        this.status = Objects.requireNonNull(status);
        this.riskProfile = Objects.requireNonNull(riskProfile);
        this.onboardedAt = Objects.requireNonNull(onboardedAt);
        this.lastUpdatedAt = Objects.requireNonNull(lastUpdatedAt);
    }

    /** Factory: onboard a new customer. */
    public static Customer onboard(CustomerId id, String legalName, CountryCode residency) {
        if (id == null) throw new IllegalArgumentException("CustomerId is required");
        if (legalName == null || legalName.isBlank()) {
            throw new IllegalArgumentException("legalName is required");
        }
        Objects.requireNonNull(residency, "residency is required");

        Instant now = Instant.now();
        Customer c = new Customer(id, legalName,
                VerificationStatus.PENDING,
                RiskProfile.standard(residency),
                now, now);
        c.uncommittedEvents.add(CustomerOnboarded.now(id, legalName, residency.value()));
        return c;
    }

    public void startVerification() {
        if (!status.canTransitionTo(VerificationStatus.IN_PROGRESS)) {
            throw new IllegalVerificationTransitionException(status, VerificationStatus.IN_PROGRESS);
        }
        this.status = VerificationStatus.IN_PROGRESS;
        this.lastUpdatedAt = Instant.now();
    }

    public void verify(String verifiedBy) {
        if (verifiedBy == null || verifiedBy.isBlank()) {
            throw new IllegalArgumentException("verifiedBy is required");
        }
        if (!status.canTransitionTo(VerificationStatus.VERIFIED)) {
            throw new IllegalVerificationTransitionException(status, VerificationStatus.VERIFIED);
        }
        this.status = VerificationStatus.VERIFIED;
        this.lastUpdatedAt = Instant.now();
        uncommittedEvents.add(CustomerVerified.now(id, verifiedBy));
    }

    public void reject(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        if (!status.canTransitionTo(VerificationStatus.REJECTED)) {
            throw new IllegalVerificationTransitionException(status, VerificationStatus.REJECTED);
        }
        this.status = VerificationStatus.REJECTED;
        this.lastUpdatedAt = Instant.now();
        uncommittedEvents.add(CustomerRejected.now(id, reason));
    }

    /**
     * Update the risk profile. Emits CustomerRiskUpdated only if the
     * relevant outward-facing fields actually changed — we don't spam
     * downstream services with no-op events.
     */
    public void updateRiskProfile(RiskProfile newProfile, String reason) {
        Objects.requireNonNull(newProfile, "newProfile is required");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }

        RiskProfile previous = this.riskProfile;
        boolean materialChange =
                previous.tier() != newProfile.tier()
                || previous.politicallyExposed() != newProfile.politicallyExposed()
                || previous.sanctioned() != newProfile.sanctioned();

        this.riskProfile = newProfile;
        this.lastUpdatedAt = Instant.now();

        if (materialChange) {
            uncommittedEvents.add(CustomerRiskUpdated.now(
                    id,
                    previous.tier(),
                    newProfile.tier(),
                    newProfile.politicallyExposed(),
                    newProfile.sanctioned(),
                    reason
            ));
        }
    }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> snapshot = List.copyOf(uncommittedEvents);
        uncommittedEvents.clear();
        return snapshot;
    }

    public List<DomainEvent> peekDomainEvents() {
        return Collections.unmodifiableList(uncommittedEvents);
    }

    public CustomerId id() { return id; }
    public String legalName() { return legalName; }
    public VerificationStatus status() { return status; }
    public RiskProfile riskProfile() { return riskProfile; }
    public Instant onboardedAt() { return onboardedAt; }
    public Instant lastUpdatedAt() { return lastUpdatedAt; }

    public boolean isVerified() {
        return status.isVerified();
    }
}

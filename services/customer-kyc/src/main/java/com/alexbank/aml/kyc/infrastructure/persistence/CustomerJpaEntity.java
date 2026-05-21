package com.alexbank.aml.kyc.infrastructure.persistence;

import com.alexbank.aml.kyc.domain.model.RiskTier;
import com.alexbank.aml.kyc.domain.model.VerificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "customers")
public class CustomerJpaEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "legal_name", nullable = false, length = 256)
    private String legalName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private VerificationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_tier", nullable = false, length = 32)
    private RiskTier riskTier;

    @Column(name = "politically_exposed", nullable = false)
    private boolean politicallyExposed;

    @Column(name = "sanctioned", nullable = false)
    private boolean sanctioned;

    @Column(name = "residency_country", nullable = false, length = 2)
    private String residencyCountry;

    @Column(name = "onboarded_at", nullable = false)
    private Instant onboardedAt;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CustomerJpaEntity() {}

    public CustomerJpaEntity(String id, String legalName, VerificationStatus status,
                             RiskTier riskTier, boolean politicallyExposed,
                             boolean sanctioned, String residencyCountry,
                             Instant onboardedAt, Instant lastUpdatedAt) {
        this.id = id;
        this.legalName = legalName;
        this.status = status;
        this.riskTier = riskTier;
        this.politicallyExposed = politicallyExposed;
        this.sanctioned = sanctioned;
        this.residencyCountry = residencyCountry;
        this.onboardedAt = onboardedAt;
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public String getId() { return id; }
    public String getLegalName() { return legalName; }
    public VerificationStatus getStatus() { return status; }
    public RiskTier getRiskTier() { return riskTier; }
    public boolean isPoliticallyExposed() { return politicallyExposed; }
    public boolean isSanctioned() { return sanctioned; }
    public String getResidencyCountry() { return residencyCountry; }
    public Instant getOnboardedAt() { return onboardedAt; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public long getVersion() { return version; }

    void apply(VerificationStatus status, RiskTier tier, boolean pep,
               boolean sanctioned, Instant lastUpdatedAt) {
        this.status = status;
        this.riskTier = tier;
        this.politicallyExposed = pep;
        this.sanctioned = sanctioned;
        this.lastUpdatedAt = lastUpdatedAt;
    }
}

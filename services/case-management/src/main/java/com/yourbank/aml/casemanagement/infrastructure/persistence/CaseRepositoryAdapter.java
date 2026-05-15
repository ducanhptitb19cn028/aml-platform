package com.yourbank.aml.casemanagement.infrastructure.persistence;

import com.yourbank.aml.casemanagement.application.port.CaseRepository;
import com.yourbank.aml.casemanagement.domain.model.Case;
import com.yourbank.aml.casemanagement.domain.model.CaseId;
import com.yourbank.aml.casemanagement.domain.model.RiskScore;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Adapter implementing the outbound port using JPA.
 * Maps between the rich domain model and the persistence entity.
 */
@Component
class CaseRepositoryAdapter implements CaseRepository {

    private final CaseJpaRepository jpa;

    CaseRepositoryAdapter(CaseJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(Case aggregate) {
        CaseJpaEntity entity = jpa.findById(aggregate.id().value())
                .map(existing -> {
                    existing.apply(
                            aggregate.status(),
                            aggregate.assignedInvestigator().orElse(null),
                            aggregate.lastUpdatedAt()
                    );
                    return existing;
                })
                .orElseGet(() -> new CaseJpaEntity(
                        aggregate.id().value(),
                        aggregate.alertId(),
                        aggregate.customerId(),
                        aggregate.riskScore().value(),
                        aggregate.status(),
                        aggregate.assignedInvestigator().orElse(null),
                        aggregate.openedAt(),
                        aggregate.lastUpdatedAt()
                ));
        jpa.save(entity);
    }

    @Override
    public Optional<Case> findById(CaseId id) {
        return jpa.findById(id.value()).map(this::toDomain);
    }

    private Case toDomain(CaseJpaEntity e) {
        return new Case(
                new CaseId(e.getId()),
                e.getAlertId(),
                e.getCustomerId(),
                new RiskScore(e.getRiskScore()),
                e.getStatus(),
                e.getAssignedInvestigator(),
                e.getOpenedAt(),
                e.getLastUpdatedAt()
        );
    }
}

package com.alexbank.aml.monitoring.infrastructure.persistence;

import com.alexbank.aml.monitoring.application.port.AlertRepository;
import com.alexbank.aml.monitoring.domain.model.Alert;
import com.alexbank.aml.monitoring.domain.model.AlertId;
import com.alexbank.aml.monitoring.domain.model.TransactionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AlertJpaRepository extends JpaRepository<AlertJpaEntity, UUID> {}

@Component
class AlertRepositoryAdapter implements AlertRepository {

    private final AlertJpaRepository jpa;

    AlertRepositoryAdapter(AlertJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(Alert alert) {
        AlertJpaEntity entity = new AlertJpaEntity(
                alert.id().value(),
                alert.transactionId().value(),
                alert.customerId(),
                alert.riskScore(),
                String.join(",", alert.firedRuleIds()),
                alert.rationale(),
                alert.raisedAt()
        );
        jpa.save(entity);
    }

    @Override
    public Optional<Alert> findById(AlertId id) {
        return jpa.findById(id.value()).map(this::toDomain);
    }

    private Alert toDomain(AlertJpaEntity e) {
        List<String> firedIds = e.getFiredRuleIds().isBlank()
                ? List.of()
                : Arrays.asList(e.getFiredRuleIds().split(","));
        return new Alert(
                new AlertId(e.getId()),
                new TransactionId(e.getTransactionId()),
                e.getCustomerId(),
                e.getRiskScore(),
                firedIds,
                e.getRationale(),
                e.getRaisedAt()
        );
    }
}

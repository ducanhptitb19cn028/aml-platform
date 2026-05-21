package com.yourbank.aml.monitoring.application.port;

import com.yourbank.aml.monitoring.domain.model.Alert;
import com.yourbank.aml.monitoring.domain.model.AlertId;

import java.util.Optional;

public interface AlertRepository {
    void save(Alert alert);
    Optional<Alert> findById(AlertId id);
}

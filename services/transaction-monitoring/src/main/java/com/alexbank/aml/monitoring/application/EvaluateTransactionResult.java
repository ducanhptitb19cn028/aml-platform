package com.alexbank.aml.monitoring.application;

import com.alexbank.aml.monitoring.domain.model.AlertId;
import com.alexbank.aml.monitoring.domain.model.TransactionId;

import java.util.Optional;

/**
 * The outcome of evaluating a transaction. Either the transaction was
 * clean (no alert) or an alert was raised.
 */
public record EvaluateTransactionResult(
        TransactionId transactionId,
        int riskScore,
        Optional<AlertId> alertId
) {
    public boolean alerted() {
        return alertId.isPresent();
    }
}

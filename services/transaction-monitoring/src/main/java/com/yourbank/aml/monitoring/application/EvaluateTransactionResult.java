package com.yourbank.aml.monitoring.application;

import com.yourbank.aml.monitoring.domain.model.AlertId;
import com.yourbank.aml.monitoring.domain.model.TransactionId;

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

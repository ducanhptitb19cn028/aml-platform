package com.yourbank.aml.monitoring.domain.rule;

import com.yourbank.aml.monitoring.domain.model.Transaction;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Context for rule evaluation — the Transaction under scrutiny plus
 * any historical context the rule might need (recent transactions,
 * customer profile).
 *
 * Why a context object: it lets rules ask for the data they need without
 * the engine pre-computing every possible feature. Each rule declares
 * what it depends on, and we can later optimise by only fetching what
 * the active rule set asks for.
 */
public record RuleContext(
        Transaction transaction,
        List<Transaction> customerHistory,
        Duration historyWindow
) {
    public RuleContext {
        Objects.requireNonNull(transaction, "transaction is required");
        Objects.requireNonNull(customerHistory, "customerHistory is required");
        Objects.requireNonNull(historyWindow, "historyWindow is required");
        customerHistory = List.copyOf(customerHistory);  // defensive copy
    }
}

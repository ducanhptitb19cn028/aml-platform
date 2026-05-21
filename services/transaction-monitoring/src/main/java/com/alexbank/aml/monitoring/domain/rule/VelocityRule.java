package com.yourbank.aml.monitoring.domain.rule;

import com.yourbank.aml.monitoring.domain.model.Transaction;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * AML-202 / Velocity.
 *
 * Triggers when a customer makes more than {@code maxTransactions}
 * transactions within {@code window} of the current transaction's time.
 *
 * Velocity is one of the classic indicators of mule account activity —
 * an account being used as a pass-through to break a clear audit trail.
 */
public final class VelocityRule implements Rule {

    public static final String ID = "AML-202";

    private final int maxTransactions;
    private final Duration window;

    public VelocityRule(int maxTransactions, Duration window) {
        if (maxTransactions <= 0) {
            throw new IllegalArgumentException("maxTransactions must be positive");
        }
        this.maxTransactions = maxTransactions;
        this.window = Objects.requireNonNull(window);
    }

    @Override
    public String id() { return ID; }

    @Override
    public RuleVerdict evaluate(RuleContext ctx) {
        Transaction tx = ctx.transaction();
        Instant cutoff = tx.occurredAt().minus(window);

        long countInWindow = ctx.customerHistory().stream()
                .filter(h -> !h.id().equals(tx.id()))
                .filter(h -> h.customerId().equals(tx.customerId()))
                .filter(h -> !h.occurredAt().isBefore(cutoff))
                .filter(h -> !h.occurredAt().isAfter(tx.occurredAt()))
                .count();

        // +1 for the current transaction
        long total = countInWindow + 1;

        if (total > maxTransactions) {
            int contribution = Math.min(70, (int) ((total - maxTransactions) * 15));
            String rationale = "%d transactions in %s exceeds limit of %d"
                    .formatted(total, window, maxTransactions);
            return RuleVerdict.fired(ID, contribution, rationale);
        }
        return RuleVerdict.notFired(ID);
    }
}

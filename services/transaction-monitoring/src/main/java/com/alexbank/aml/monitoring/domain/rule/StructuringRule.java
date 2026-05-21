package com.alexbank.aml.monitoring.domain.rule;

import com.alexbank.aml.monitoring.domain.model.Money;
import com.alexbank.aml.monitoring.domain.model.Transaction;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * AML-303 / Structuring (also known as "smurfing").
 *
 * Triggers when a customer makes multiple sub-threshold transactions
 * that, in aggregate, cross the reporting threshold within a window.
 * Structuring is a deliberate evasion pattern: instead of one £15k
 * transfer (which would file a CTR), the customer makes two £8k
 * transfers a day apart.
 *
 * The detection logic:
 *   - Each individual transaction is BELOW the high-value threshold
 *   - Their SUM within a window meets or exceeds the threshold
 *   - At least {@code minTransactions} contribute to the sum
 */
public final class StructuringRule implements Rule {

    public static final String ID = "AML-303";

    private final Money threshold;
    private final Duration window;
    private final int minTransactions;

    public StructuringRule(Money threshold, Duration window, int minTransactions) {
        this.threshold = Objects.requireNonNull(threshold);
        this.window = Objects.requireNonNull(window);
        if (minTransactions < 2) {
            throw new IllegalArgumentException("minTransactions must be >= 2");
        }
        this.minTransactions = minTransactions;
    }

    @Override
    public String id() { return ID; }

    @Override
    public RuleVerdict evaluate(RuleContext ctx) {
        Transaction tx = ctx.transaction();

        // Structuring requires sub-threshold individual transactions.
        // If the current transaction is itself above threshold, this rule
        // does not apply (HighValueRule handles that case).
        // Skip entirely if transaction currency doesn't match the configured threshold currency.
        if (!tx.amount().currency().equals(threshold.currency())) {
            return RuleVerdict.notFired(ID);
        }
        if (tx.amount().isGreaterThanOrEqual(threshold)) {
            return RuleVerdict.notFired(ID);
        }

        Instant cutoff = tx.occurredAt().minus(window);
        Currency currency = tx.amount().currency();

        List<Transaction> contributing = ctx.customerHistory().stream()
                .filter(h -> h.customerId().equals(tx.customerId()))
                .filter(h -> h.amount().currency().equals(currency))
                .filter(h -> !h.amount().isGreaterThanOrEqual(threshold))
                .filter(h -> !h.occurredAt().isBefore(cutoff))
                .filter(h -> !h.occurredAt().isAfter(tx.occurredAt()))
                .toList();

        // Include the current transaction in the sum
        Money sum = contributing.stream()
                .map(Transaction::amount)
                .reduce(new Money(BigDecimal.ZERO, currency), Money::plus);

        // contributing already includes the current tx if it's in history;
        // if not, add it to the count and sum
        boolean currentInHistory = contributing.stream()
                .anyMatch(h -> h.id().equals(tx.id()));
        int contributingCount = contributing.size() + (currentInHistory ? 0 : 1);
        if (!currentInHistory) {
            sum = sum.plus(tx.amount());
        }

        if (contributingCount >= minTransactions && sum.isGreaterThanOrEqual(threshold)) {
            String rationale = "%d sub-threshold transactions sum to %s in %s, crossing threshold %s"
                    .formatted(contributingCount, sum.amount(), window, threshold.amount());
            return RuleVerdict.fired(ID, 75, rationale);
        }
        return RuleVerdict.notFired(ID);
    }
}

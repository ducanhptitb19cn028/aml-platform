package com.yourbank.aml.monitoring.domain.rule;

import com.yourbank.aml.monitoring.domain.model.Money;

import java.util.Objects;

/**
 * AML-101 / High Value Transaction.
 *
 * Triggers when a single transaction equals or exceeds a threshold.
 * The classic FinCEN/HMRC threshold is ~£10k or equivalent — any larger
 * cash-like movement crosses the reporting threshold.
 *
 * Risk contribution scales with how much the threshold is exceeded,
 * capped at 80 (a single high-value transaction alone is not enough
 * to escalate; pattern-based rules add the rest).
 */
public final class HighValueRule implements Rule {

    public static final String ID = "AML-101";

    private final Money threshold;

    public HighValueRule(Money threshold) {
        this.threshold = Objects.requireNonNull(threshold);
    }

    @Override
    public String id() { return ID; }

    @Override
    public RuleVerdict evaluate(RuleContext ctx) {
        Money amount = ctx.transaction().amount();
        if (!amount.currency().equals(threshold.currency())) {
            return RuleVerdict.notFired(ID);
        }
        if (amount.isGreaterThanOrEqual(threshold)) {
            int contribution = scaleContribution(amount, threshold);
            String rationale = "amount %s exceeds threshold %s"
                    .formatted(amount.amount(), threshold.amount());
            return RuleVerdict.fired(ID, contribution, rationale);
        }
        return RuleVerdict.notFired(ID);
    }

    private static int scaleContribution(Money amount, Money threshold) {
        // Linear scale from 40 (at threshold) to 80 (at 5x threshold).
        double ratio = amount.amount().doubleValue() / threshold.amount().doubleValue();
        if (ratio >= 5.0) return 80;
        return (int) Math.min(80, 40 + (ratio - 1.0) * 10);
    }
}

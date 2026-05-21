package com.alexbank.aml.monitoring.domain.rule;

import java.util.Objects;

/**
 * The result of one rule evaluating one transaction.
 *
 * Why not just a boolean: senior code tells you *why* something fired,
 * not just whether. The rationale becomes the alert reason, the audit
 * record, and a feature for the anomaly-detection model.
 */
public record RuleVerdict(
        String ruleId,
        boolean fired,
        int riskContribution,
        String rationale
) {
    public RuleVerdict {
        Objects.requireNonNull(ruleId, "ruleId is required");
        if (riskContribution < 0 || riskContribution > 100) {
            throw new IllegalArgumentException(
                    "riskContribution must be 0..100, got " + riskContribution);
        }
        if (fired && (rationale == null || rationale.isBlank())) {
            throw new IllegalArgumentException(
                    "fired verdicts must carry a rationale for audit");
        }
    }

    public static RuleVerdict notFired(String ruleId) {
        return new RuleVerdict(ruleId, false, 0, "rule did not match");
    }

    public static RuleVerdict fired(String ruleId, int riskContribution, String rationale) {
        return new RuleVerdict(ruleId, true, riskContribution, rationale);
    }
}

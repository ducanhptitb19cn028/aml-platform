package com.yourbank.aml.monitoring.domain.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Domain Service that runs all configured rules against a context and
 * combines their verdicts into a single risk score.
 *
 * Why a Domain Service: rule composition is business logic but doesn't
 * belong on any individual aggregate. Eric Evans's classic Domain Service
 * pattern fits exactly.
 *
 * Why bounded score combination (capped at 100): risk scores are not
 * additive in reality. Two rules each contributing 60 should not produce
 * 120 — they should saturate near the upper bound. We use a simple
 * "diminishing returns" combiner that is monotonic and stays in [0,100].
 */
public final class RuleEngine {

    private final List<Rule> rules;

    public RuleEngine(List<Rule> rules) {
        Objects.requireNonNull(rules, "rules cannot be null");
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("RuleEngine requires at least one rule");
        }
        this.rules = List.copyOf(rules);
    }

    /**
     * Evaluate all rules against the context.
     * Returns a verdict per rule plus the combined risk score.
     */
    public EngineResult evaluate(RuleContext ctx) {
        List<RuleVerdict> verdicts = new ArrayList<>(rules.size());
        for (Rule rule : rules) {
            verdicts.add(rule.evaluate(ctx));
        }
        int combined = combine(verdicts);
        return new EngineResult(verdicts, combined);
    }

    /**
     * Combine individual rule contributions using a 1 - product-of-complements
     * formula. This yields a monotonic function that:
     *   - returns 0 when no rules fire
     *   - approaches but never exceeds 100
     *   - rewards multiple firing rules without overflowing
     */
    public static int combine(List<RuleVerdict> verdicts) {
        double remainingClean = 1.0;
        for (RuleVerdict v : verdicts) {
            if (v.fired()) {
                remainingClean *= (1.0 - v.riskContribution() / 100.0);
            }
        }
        return (int) Math.round((1.0 - remainingClean) * 100);
    }

    public record EngineResult(List<RuleVerdict> verdicts, int combinedRiskScore) {
        public EngineResult {
            verdicts = List.copyOf(verdicts);
        }

        public boolean anyFired() {
            return verdicts.stream().anyMatch(RuleVerdict::fired);
        }

        public List<RuleVerdict> firedVerdicts() {
            return verdicts.stream().filter(RuleVerdict::fired).toList();
        }
    }
}

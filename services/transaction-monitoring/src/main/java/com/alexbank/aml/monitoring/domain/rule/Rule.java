package com.alexbank.aml.monitoring.domain.rule;

/**
 * A monitoring rule. Implements the Specification pattern.
 *
 * Each rule is a self-contained predicate over a RuleContext that
 * returns a RuleVerdict. Rules are pure functions — no side effects,
 * no state. This is what makes them:
 *   - trivially unit-testable (no mocking)
 *   - safely composable (and/or/not)
 *   - parallel-safe at evaluation time
 *
 * The interface deliberately does NOT extend java.util.function.Predicate.
 * A predicate returns a boolean; we return a RuleVerdict carrying
 * rationale and risk contribution. Specification != Predicate.
 */
public interface Rule {

    /** Stable identifier used in persistence, metrics, and audit logs. */
    String id();

    /** Evaluate the rule against the given context. Pure function. */
    RuleVerdict evaluate(RuleContext context);
}

package com.alexbank.aml.monitoring.domain;

import com.alexbank.aml.monitoring.domain.rule.RuleEngine;
import com.alexbank.aml.monitoring.domain.rule.RuleVerdict;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties of the rule combination function.
 *
 * These are the invariants that MUST hold regardless of which rules
 * fire or how many. If a refactor breaks any of these, the score combiner
 * is broken.
 */
class RuleEngineProperties {

    @Property
    void combined_score_is_always_in_0_100(
            @ForAll("verdictList") List<RuleVerdict> verdicts
    ) {
        int score = RuleEngine.combine(verdicts);
        assertThat(score).isBetween(0, 100);
    }

    @Property
    void no_fired_rules_means_zero_score(
            @ForAll("notFiredVerdictList") List<RuleVerdict> verdicts
    ) {
        int score = RuleEngine.combine(verdicts);
        assertThat(score).isZero();
    }

    @Property
    void adding_a_fired_rule_never_decreases_score(
            @ForAll("baseVerdictList") List<RuleVerdict> base,
            @ForAll("firedVerdicts") RuleVerdict extra
    ) {
        int before = RuleEngine.combine(base);
        var withExtra = new java.util.ArrayList<>(base);
        withExtra.add(extra);
        int after = RuleEngine.combine(withExtra);

        assertThat(after).isGreaterThanOrEqualTo(before);
    }

    @Provide
    Arbitrary<List<RuleVerdict>> verdictList() {
        return verdicts().list().ofMinSize(0).ofMaxSize(10);
    }

    @Provide
    Arbitrary<List<RuleVerdict>> notFiredVerdictList() {
        return notFiredVerdicts().list().ofMinSize(0).ofMaxSize(5);
    }

    @Provide
    Arbitrary<List<RuleVerdict>> baseVerdictList() {
        return verdicts().list().ofMinSize(0).ofMaxSize(5);
    }

    @Provide
    Arbitrary<RuleVerdict> verdicts() {
        return Arbitraries.oneOf(firedVerdicts(), notFiredVerdicts());
    }

    @Provide
    Arbitrary<RuleVerdict> firedVerdicts() {
        return Arbitraries.integers().between(0, 100)
                .map(score -> RuleVerdict.fired("R-" + score, score, "rationale"));
    }

    @Provide
    Arbitrary<RuleVerdict> notFiredVerdicts() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8)
                .map(RuleVerdict::notFired);
    }
}

package com.alexbank.aml.monitoring.domain;

import com.alexbank.aml.monitoring.domain.model.Channel;
import com.alexbank.aml.monitoring.domain.model.CountryCode;
import com.alexbank.aml.monitoring.domain.model.Money;
import com.alexbank.aml.monitoring.domain.model.Transaction;
import com.alexbank.aml.monitoring.domain.model.TransactionId;
import com.alexbank.aml.monitoring.domain.rule.HighRiskCorridorRule;
import com.alexbank.aml.monitoring.domain.rule.HighValueRule;
import com.alexbank.aml.monitoring.domain.rule.RuleContext;
import com.alexbank.aml.monitoring.domain.rule.RuleEngine;
import com.alexbank.aml.monitoring.domain.rule.RuleVerdict;
import com.alexbank.aml.monitoring.domain.rule.StructuringRule;
import com.alexbank.aml.monitoring.domain.rule.VelocityRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule engine is the most important business logic in this service.
 * Pitest mutation testing targets this code specifically — every branch
 * here must be exercised by tests.
 */
class RuleEngineTest {

    private static final Money THRESHOLD = Money.of("10000", "GBP");
    private static final CountryCode GB = new CountryCode("GB");
    private static final CountryCode IR = new CountryCode("IR");

    private static Transaction tx(String customer, String amount, String currency,
                                  CountryCode origin, CountryCode dest, Instant when) {
        return new Transaction(
                TransactionId.generate(), customer,
                Money.of(amount, currency),
                origin, dest, Channel.FASTER_PAYMENTS, when
        );
    }

    @Nested
    @DisplayName("HighValueRule")
    class HighValue {

        @Test
        void fires_when_amount_meets_threshold() {
            Transaction t = tx("c", "10000", "GBP", GB, GB, Instant.now());
            RuleVerdict v = new HighValueRule(THRESHOLD)
                    .evaluate(new RuleContext(t, List.of(), Duration.ofDays(1)));
            assertThat(v.fired()).isTrue();
        }

        @Test
        void does_not_fire_below_threshold() {
            Transaction t = tx("c", "9999.99", "GBP", GB, GB, Instant.now());
            RuleVerdict v = new HighValueRule(THRESHOLD)
                    .evaluate(new RuleContext(t, List.of(), Duration.ofDays(1)));
            assertThat(v.fired()).isFalse();
        }

        @Test
        void contribution_scales_with_excess() {
            Transaction small = tx("c", "10000", "GBP", GB, GB, Instant.now());
            Transaction big = tx("c", "60000", "GBP", GB, GB, Instant.now());
            HighValueRule rule = new HighValueRule(THRESHOLD);

            int smallScore = rule.evaluate(new RuleContext(small, List.of(), Duration.ofDays(1)))
                    .riskContribution();
            int bigScore = rule.evaluate(new RuleContext(big, List.of(), Duration.ofDays(1)))
                    .riskContribution();

            assertThat(bigScore).isGreaterThan(smallScore);
            assertThat(bigScore).isLessThanOrEqualTo(80);
        }
    }

    @Nested
    @DisplayName("VelocityRule")
    class Velocity {

        @Test
        void fires_when_recent_count_exceeds_limit() {
            Instant now = Instant.now();
            List<Transaction> history = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                history.add(tx("c", "100", "GBP", GB, GB, now.minus(Duration.ofMinutes(i * 5L))));
            }
            Transaction current = tx("c", "100", "GBP", GB, GB, now);

            RuleVerdict v = new VelocityRule(5, Duration.ofHours(1))
                    .evaluate(new RuleContext(current, history, Duration.ofHours(1)));

            assertThat(v.fired()).isTrue();
        }

        @Test
        void does_not_fire_when_history_is_outside_window() {
            Instant now = Instant.now();
            List<Transaction> oldHistory = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                oldHistory.add(tx("c", "100", "GBP", GB, GB, now.minus(Duration.ofDays(i + 1))));
            }
            Transaction current = tx("c", "100", "GBP", GB, GB, now);

            RuleVerdict v = new VelocityRule(5, Duration.ofHours(1))
                    .evaluate(new RuleContext(current, oldHistory, Duration.ofHours(1)));

            assertThat(v.fired()).isFalse();
        }

        @Test
        void rejects_zero_max_transactions() {
            assertThatThrownBy(() -> new VelocityRule(0, Duration.ofHours(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("StructuringRule")
    class Structuring {

        @Test
        void fires_when_subthreshold_sum_crosses_threshold() {
            Instant now = Instant.now();
            List<Transaction> history = List.of(
                    tx("c", "4000", "GBP", GB, GB, now.minus(Duration.ofHours(2))),
                    tx("c", "3500", "GBP", GB, GB, now.minus(Duration.ofHours(1)))
            );
            Transaction current = tx("c", "3000", "GBP", GB, GB, now);

            RuleVerdict v = new StructuringRule(THRESHOLD, Duration.ofHours(24), 3)
                    .evaluate(new RuleContext(current, history, Duration.ofDays(1)));

            assertThat(v.fired()).isTrue();
        }

        @Test
        void ignores_above_threshold_transactions() {
            // A single high-value tx is HighValue's job, not Structuring's
            Instant now = Instant.now();
            Transaction big = tx("c", "15000", "GBP", GB, GB, now);

            RuleVerdict v = new StructuringRule(THRESHOLD, Duration.ofHours(24), 3)
                    .evaluate(new RuleContext(big, List.of(), Duration.ofDays(1)));

            assertThat(v.fired()).isFalse();
        }

        @Test
        void requires_minimum_count() {
            // Just two sub-threshold transactions summing > threshold should NOT fire
            // when minTransactions is 3
            Instant now = Instant.now();
            List<Transaction> history = List.of(
                    tx("c", "6000", "GBP", GB, GB, now.minus(Duration.ofHours(1)))
            );
            Transaction current = tx("c", "5000", "GBP", GB, GB, now);

            RuleVerdict v = new StructuringRule(THRESHOLD, Duration.ofHours(24), 3)
                    .evaluate(new RuleContext(current, history, Duration.ofDays(1)));

            assertThat(v.fired()).isFalse();
        }
    }

    @Nested
    @DisplayName("HighRiskCorridorRule")
    class Corridor {

        @Test
        void fires_on_high_risk_destination() {
            Transaction t = tx("c", "500", "GBP", GB, IR, Instant.now());
            RuleVerdict v = new HighRiskCorridorRule()
                    .evaluate(new RuleContext(t, List.of(), Duration.ofDays(1)));
            assertThat(v.fired()).isTrue();
        }

        @Test
        void does_not_fire_on_safe_corridor() {
            Transaction t = tx("c", "500", "GBP", GB, GB, Instant.now());
            RuleVerdict v = new HighRiskCorridorRule()
                    .evaluate(new RuleContext(t, List.of(), Duration.ofDays(1)));
            assertThat(v.fired()).isFalse();
        }
    }

    @Nested
    @DisplayName("Engine composition")
    class Composition {

        @Test
        void multiple_fired_rules_combine_monotonically() {
            RuleEngine engine = new RuleEngine(List.of(
                    new HighValueRule(THRESHOLD),
                    new HighRiskCorridorRule()
            ));

            Transaction big_safe = tx("c", "20000", "GBP", GB, GB, Instant.now());
            Transaction big_risky = tx("c", "20000", "GBP", GB, IR, Instant.now());

            int safeScore = engine.evaluate(new RuleContext(big_safe, List.of(), Duration.ofDays(1)))
                    .combinedRiskScore();
            int riskyScore = engine.evaluate(new RuleContext(big_risky, List.of(), Duration.ofDays(1)))
                    .combinedRiskScore();

            assertThat(riskyScore).isGreaterThan(safeScore);
            assertThat(riskyScore).isLessThanOrEqualTo(100);
        }

        @Test
        void no_fired_rules_yields_zero_score() {
            RuleEngine engine = new RuleEngine(List.of(
                    new HighValueRule(THRESHOLD),
                    new HighRiskCorridorRule()
            ));
            Transaction clean = tx("c", "50", "GBP", GB, GB, Instant.now());

            RuleEngine.EngineResult r = engine.evaluate(
                    new RuleContext(clean, List.of(), Duration.ofDays(1)));

            assertThat(r.anyFired()).isFalse();
            assertThat(r.combinedRiskScore()).isZero();
        }

        @Test
        void rejects_empty_rule_list() {
            assertThatThrownBy(() -> new RuleEngine(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

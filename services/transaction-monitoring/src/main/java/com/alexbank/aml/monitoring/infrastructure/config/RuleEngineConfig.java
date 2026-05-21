package com.alexbank.aml.monitoring.infrastructure.config;

import com.alexbank.aml.monitoring.domain.model.Money;
import com.alexbank.aml.monitoring.domain.rule.HighRiskCorridorRule;
import com.alexbank.aml.monitoring.domain.rule.HighValueRule;
import com.alexbank.aml.monitoring.domain.rule.Rule;
import com.alexbank.aml.monitoring.domain.rule.RuleEngine;
import com.alexbank.aml.monitoring.domain.rule.StructuringRule;
import com.alexbank.aml.monitoring.domain.rule.VelocityRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Wires the active rule set for production. Rule parameters live in
 * application.yml so compliance can tune them without a code change.
 *
 * In a real bank these would also be loaded from a rule store
 * (database or feature flag service) so they can be changed at runtime.
 * For this platform we keep them static — easier to reason about for
 * the research experiments.
 */
@Configuration
class RuleEngineConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RuleEngine ruleEngine(
            @Value("${aml.rules.high-value.threshold:10000}") String highValueThreshold,
            @Value("${aml.rules.high-value.currency:GBP}") String currency,
            @Value("${aml.rules.velocity.max-transactions:5}") int velocityMax,
            @Value("${aml.rules.velocity.window-hours:1}") int velocityWindowHours,
            @Value("${aml.rules.structuring.window-hours:24}") int structuringWindowHours,
            @Value("${aml.rules.structuring.min-transactions:3}") int structuringMin
    ) {
        Money threshold = Money.of(highValueThreshold, currency);
        List<Rule> rules = List.of(
                new HighValueRule(threshold),
                new VelocityRule(velocityMax, Duration.ofHours(velocityWindowHours)),
                new StructuringRule(threshold, Duration.ofHours(structuringWindowHours), structuringMin),
                new HighRiskCorridorRule()
        );
        return new RuleEngine(rules);
    }
}

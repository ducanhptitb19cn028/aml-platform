package com.yourbank.aml.monitoring.domain.rule;

import com.yourbank.aml.monitoring.domain.model.Transaction;

/**
 * AML-404 / High-Risk Corridor.
 *
 * Triggers when a transaction crosses into or out of a FATF high-risk
 * jurisdiction. Geography alone doesn't make a transaction suspicious,
 * but it raises the risk band and combines with other signals.
 *
 * Risk contribution is modest (35) so this rule rarely fires alone —
 * it boosts other rules into alert territory rather than firing on its own.
 */
public final class HighRiskCorridorRule implements Rule {

    public static final String ID = "AML-404";

    @Override
    public String id() { return ID; }

    @Override
    public RuleVerdict evaluate(RuleContext ctx) {
        var tx = ctx.transaction();
        boolean originHigh = tx.originCountry().isHighRisk();
        boolean destinationHigh = tx.destinationCountry().isHighRisk();

        if (originHigh || destinationHigh) {
            String direction = originHigh && destinationHigh ? "both endpoints"
                    : originHigh ? "origin " + tx.originCountry().value()
                    : "destination " + tx.destinationCountry().value();
            return RuleVerdict.fired(ID, 35,
                    "high-risk jurisdiction involved: " + direction);
        }
        return RuleVerdict.notFired(ID);
    }
}

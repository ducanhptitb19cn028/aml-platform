package com.alexbank.aml.monitoring.domain.model;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Money is a proper value object — not a double, not a long pence.
 *
 * Why BigDecimal: financial regulation requires deterministic decimal
 * arithmetic. Floating-point representations of money are a junior mistake.
 *
 * Why Currency: amounts are not comparable across currencies without a
 * conversion step. Encoding the currency on the value object makes it
 * impossible to accidentally compare 1000 GBP to 1000 USD.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount cannot be negative: " + amount);
        }
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public boolean isGreaterThanOrEqual(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) >= 0;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot compare/combine different currencies: %s vs %s"
                            .formatted(currency.getCurrencyCode(), other.currency.getCurrencyCode()));
        }
    }
}

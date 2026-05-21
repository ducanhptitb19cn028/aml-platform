package com.yourbank.aml.monitoring.domain.model;

/**
 * The payment channel through which the transaction was initiated.
 * Different channels have different baseline risk profiles.
 */
public enum Channel {
    SEPA,           // EU bank transfer
    FASTER_PAYMENTS, // UK domestic
    SWIFT,          // international wire
    CARD,           // card payment
    CASH_DEPOSIT,   // physical cash deposit
    CRYPTO_OFFRAMP  // exchange-to-fiat
}

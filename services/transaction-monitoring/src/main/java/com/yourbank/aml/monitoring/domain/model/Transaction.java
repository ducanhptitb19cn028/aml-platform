package com.yourbank.aml.monitoring.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A transaction observed by the bank. This is an aggregate root in the
 * Monitoring context — it is monitored, not modified. The Payments context
 * owns the lifecycle (initiated, authorised, settled). Here we just see
 * the resulting facts.
 *
 * Note: in the Monitoring context, Transaction is a *read-only* aggregate
 * after construction. Rule evaluation does not mutate it. This is what
 * makes parallel rule evaluation safe.
 */
public final class Transaction {

    private final TransactionId id;
    private final String customerId;
    private final Money amount;
    private final CountryCode originCountry;
    private final CountryCode destinationCountry;
    private final Channel channel;
    private final Instant occurredAt;

    public Transaction(TransactionId id, String customerId, Money amount,
                       CountryCode originCountry, CountryCode destinationCountry,
                       Channel channel, Instant occurredAt) {
        this.id = Objects.requireNonNull(id);
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }
        this.customerId = customerId;
        this.amount = Objects.requireNonNull(amount);
        this.originCountry = Objects.requireNonNull(originCountry);
        this.destinationCountry = Objects.requireNonNull(destinationCountry);
        this.channel = Objects.requireNonNull(channel);
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }

    public TransactionId id()                  { return id; }
    public String customerId()                 { return customerId; }
    public Money amount()                      { return amount; }
    public CountryCode originCountry()         { return originCountry; }
    public CountryCode destinationCountry()    { return destinationCountry; }
    public Channel channel()                   { return channel; }
    public Instant occurredAt()                { return occurredAt; }

    public boolean crossesBorder() {
        return !originCountry.equals(destinationCountry);
    }
}

package com.alexbank.aml.monitoring.infrastructure.persistence;

import com.alexbank.aml.monitoring.domain.model.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class TransactionJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "origin_country", nullable = false, length = 2)
    private String originCountry;

    @Column(name = "destination_country", nullable = false, length = 2)
    private String destinationCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    private Channel channel;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected TransactionJpaEntity() {}

    public TransactionJpaEntity(UUID id, String customerId, BigDecimal amount, String currency,
                                String originCountry, String destinationCountry,
                                Channel channel, Instant occurredAt) {
        this.id = id;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency;
        this.originCountry = originCountry;
        this.destinationCountry = destinationCountry;
        this.channel = channel;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public String getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getOriginCountry() { return originCountry; }
    public String getDestinationCountry() { return destinationCountry; }
    public Channel getChannel() { return channel; }
    public Instant getOccurredAt() { return occurredAt; }
}

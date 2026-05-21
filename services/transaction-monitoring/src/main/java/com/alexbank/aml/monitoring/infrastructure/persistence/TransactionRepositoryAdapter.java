package com.alexbank.aml.monitoring.infrastructure.persistence;

import com.alexbank.aml.monitoring.application.port.TransactionRepository;
import com.alexbank.aml.monitoring.domain.model.CountryCode;
import com.alexbank.aml.monitoring.domain.model.Money;
import com.alexbank.aml.monitoring.domain.model.Transaction;
import com.alexbank.aml.monitoring.domain.model.TransactionId;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

@Component
class TransactionRepositoryAdapter implements TransactionRepository {

    private final TransactionJpaRepository jpa;
    private final Clock clock;

    TransactionRepositoryAdapter(TransactionJpaRepository jpa, Clock clock) {
        this.jpa = jpa;
        this.clock = clock;
    }

    @Override
    public void save(Transaction tx) {
        TransactionJpaEntity entity = new TransactionJpaEntity(
                tx.id().value(),
                tx.customerId(),
                tx.amount().amount(),
                tx.amount().currency().getCurrencyCode(),
                tx.originCountry().value(),
                tx.destinationCountry().value(),
                tx.channel(),
                tx.occurredAt()
        );
        jpa.save(entity);
    }

    @Override
    public Optional<Transaction> findById(TransactionId id) {
        return jpa.findById(id.value()).map(this::toDomain);
    }

    @Override
    public List<Transaction> findByCustomerWithin(String customerId, Duration window) {
        return jpa.findByCustomerSince(customerId, clock.instant().minus(window))
                .stream().map(this::toDomain).toList();
    }

    private Transaction toDomain(TransactionJpaEntity e) {
        return new Transaction(
                new TransactionId(e.getId()),
                e.getCustomerId(),
                new Money(e.getAmount(), Currency.getInstance(e.getCurrency())),
                new CountryCode(e.getOriginCountry()),
                new CountryCode(e.getDestinationCountry()),
                e.getChannel(),
                e.getOccurredAt()
        );
    }
}

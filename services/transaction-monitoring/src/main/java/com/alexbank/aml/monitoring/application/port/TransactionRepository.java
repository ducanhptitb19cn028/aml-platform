package com.alexbank.aml.monitoring.application.port;

import com.alexbank.aml.monitoring.domain.model.Transaction;
import com.alexbank.aml.monitoring.domain.model.TransactionId;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository {
    void save(Transaction tx);
    Optional<Transaction> findById(TransactionId id);
    List<Transaction> findByCustomerWithin(String customerId, Duration window);
}

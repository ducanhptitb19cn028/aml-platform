package com.yourbank.aml.monitoring.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface TransactionJpaRepository extends JpaRepository<TransactionJpaEntity, UUID> {

    @Query("""
        SELECT t FROM TransactionJpaEntity t
        WHERE t.customerId = :customerId
          AND t.occurredAt >= :since
        ORDER BY t.occurredAt DESC
    """)
    List<TransactionJpaEntity> findByCustomerSince(
            @Param("customerId") String customerId,
            @Param("since") Instant since);
}

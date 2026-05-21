package com.yourbank.aml.kyc.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface OutboxJpaRepository extends JpaRepository<OutboxJpaEntity, UUID> {

    @Query(value = """
            SELECT * FROM outbox
            WHERE dispatched_at IS NULL
            ORDER BY created_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<OutboxJpaEntity> findPendingForDispatch(@Param("batchSize") int batchSize);
}

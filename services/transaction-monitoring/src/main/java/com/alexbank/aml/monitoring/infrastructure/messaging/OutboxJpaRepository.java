package com.alexbank.aml.monitoring.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface OutboxJpaRepository extends JpaRepository<OutboxJpaEntity, UUID> {

    /**
     * Fetch the next batch of pending events, locking them so concurrent
     * worker instances don't dispatch the same row twice.
     *
     * SKIP LOCKED is the key: instead of blocking when another worker
     * already locked a row, we skip it and pick a different one. That's
     * what lets us run multiple workers safely.
     *
     * The lock is released when the surrounding transaction commits.
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE dispatched_at IS NULL
            ORDER BY created_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<OutboxJpaEntity> findPendingForDispatch(@Param("batchSize") int batchSize);
}

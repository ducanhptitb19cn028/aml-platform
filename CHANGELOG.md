# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] — third service + synchronous contract pattern

### Added — Customer / KYC bounded context
- New `customer-kyc` service. Master data store for customer identity,
  verification status, and risk profile.
- `Customer` aggregate with full lifecycle state machine (PENDING →
  IN_PROGRESS → VERIFIED, with REJECTED as terminal and PERIODIC_REVIEW
  as a re-verification cycle).
- `RiskProfile` value object with cross-field invariant: a sanctioned
  customer must be in PROHIBITED tier (enforced in code AND as a DB
  CHECK constraint).
- `CustomerRiskUpdated` cross-context event, published only when
  outward-facing fields actually change (no spam).
- Same outbox pattern as the other services for event publication.
- Helm chart with the tightest SLOs in the platform (P99 < 150ms) —
  KYC is on the hot path of every transaction evaluation.

### Added — synchronous contract pattern (the architectural delta)
- `KycClient` in both Monitoring and Case Management — both services
  consume `GET /api/v1/customers/{id}` synchronously.
- Two consumer-side Pact tests (one per consumer) declare expected
  response shape with different field subsets.
- One provider-side Pact test in customer-kyc verifies it satisfies
  ALL consumer expectations.
- Demonstrates the **two contract types in one platform**: async Kafka
  (Pact-locked) for state-change propagation, sync HTTP (Pact-locked)
  for slow-changing master data lookups.

### Verified
- 59 pure-Java domain classes compile clean (up from 41 in v0.2).
- 27 KYC domain assertions pass (lifecycle, validation, no-op
  detection, sanctioned-implies-prohibited invariant).
- 12 three-service end-to-end assertions pass: KYC PEP designation →
  Monitoring rule evaluation finds high-risk → AlertRaised published
  → Case opened with same customerId → investigator escalates → SAR
  filed. The same `customerId` threads through all three services.

## [0.2.0] — production-correctness pass

### Added — messaging guarantees
- **Transactional outbox pattern** in both services. Domain events are
  written to an `outbox` table in the same DB transaction as the
  aggregate, then forwarded to Kafka by a scheduled `OutboxDispatcher`.
  Eliminates the commit-then-send race that the previous direct
  publisher had.
- **Consumer idempotency** via a `processed_events` table in
  case-management. Duplicate `AlertRaised` deliveries are detected and
  skipped, ensuring exactly-one Case per alert.
- `OutboxDispatcher` uses `FOR UPDATE SKIP LOCKED` so multiple
  instances run safely in parallel.

### Added — tests
- `TransactionRepositoryAdapterIT` — Testcontainers integration test
  for the monitoring service's JPA adapter, including the
  customer/window query that powers velocity & structuring rules.
- `OutboxIT` — verifies that the outbox publisher writes correct rows
  with valid JSONB payloads, exercised against real Postgres.
- `AlertRaisedListenerTest` — unit-tests the listener's idempotency
  behaviour, including a 100-thread concurrency race.

### Changed
- Both Kafka producers now use `KafkaTemplate<String, String>` (sending
  pre-serialised JSON) instead of `KafkaTemplate<String, DomainEvent>`.
  This decouples wire format from Java types: what's in the outbox is
  the same JSON byte-for-byte that goes to Kafka.
- `application.yml` exposes outbox tuning knobs:
  `aml.outbox.poll-interval-ms`, `batch-size`, `send-timeout-seconds`.
- `BUSINESS_LOGIC.md` adds section 7 ("Messaging guarantees") covering
  the production-correctness story in business terms.

### Verified
- 41 pure-Java domain classes compile clean against JDK 21.
- 9 end-to-end integration assertions pass (suspicious tx → rules fire
  → alert raised → event has unique eventId → first delivery opens a
  case → duplicate deliveries are detected → distinct events open
  distinct cases → causal chain preserved).
- 5 idempotency invariants pass, including a 100-thread concurrency
  race where exactly one thread wins.

## [0.1.0] — initial release

- Two bounded contexts implemented: Case Management and Transaction
  Monitoring.
- Strict hexagonal architecture enforced by ArchUnit.
- Domain-Driven Design with rich aggregates, value objects, and
  domain events.
- Specification-pattern rule engine with four real AML rules
  (HighValue, Velocity, Structuring, HighRiskCorridor).
- Pact contract tests on both sides of the AlertRaised event.
- Property-based tests for the rule engine combiner.
- Pitest mutation testing on both domain layers (≥85% threshold).
- Helm charts for both services with HPA, PDB, NetworkPolicy,
  ServiceMonitor.
- docker-compose with Postgres-per-service, Kafka, Prometheus,
  Tempo, Loki.
- Full GitHub Actions CI: build → test → contract → integration →
  Docker.

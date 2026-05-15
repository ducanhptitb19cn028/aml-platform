# Architecture

This document explains **how the code is organised** and **why**. For the
domain model itself, read [`BUSINESS_LOGIC.md`](BUSINESS_LOGIC.md). For the
research framing, read [`research-design.md`](research-design.md).

---

## 1. Hexagonal architecture in one picture

```
   ┌─────────────────────────────────────────────────────────────┐
   │                    infrastructure/                          │
   │                                                             │
   │  ┌─────────────┐   ┌──────────────┐   ┌──────────────────┐  │
   │  │   api/      │   │ persistence/ │   │   messaging/     │  │
   │  │  REST,      │   │  JPA entity  │   │  Kafka producer  │  │
   │  │  exception  │   │  + adapter   │   │  + span events   │  │
   │  │  handler    │   │              │   │                  │  │
   │  └──────┬──────┘   └──────┬───────┘   └────────┬─────────┘  │
   │         │                 │                     │            │
   │         │   implements    │  implements         │            │
   │         │   inbound port  │  outbound port      │            │
   │  ┌──────▼─────────────────▼─────────────────────▼────────┐   │
   │  │                    application/                      │   │
   │  │    CaseApplicationService                            │   │
   │  │      ↑ uses domain                                   │   │
   │  │      ↓ depends on ports (interfaces)                 │   │
   │  │                                                      │   │
   │  │   ports:                                             │   │
   │  │     CaseRepository                                   │   │
   │  │     DomainEventPublisher                             │   │
   │  │   commands:                                          │   │
   │  │     OpenCaseCommand, AssignCaseCommand, ...          │   │
   │  │                                                      │   │
   │  │   ┌────────────────────────────────────────────┐     │   │
   │  │   │                domain/                     │     │   │
   │  │   │   Case (aggregate root)                    │     │   │
   │  │   │   CaseStatus, RiskScore, CaseId            │     │   │
   │  │   │   CaseOpened, CaseAssigned, ...            │     │   │
   │  │   │                                            │     │   │
   │  │   │   ZERO dependencies on Spring or JPA       │     │   │
   │  │   └────────────────────────────────────────────┘     │   │
   │  └──────────────────────────────────────────────────────┘   │
   └─────────────────────────────────────────────────────────────┘
```

**The dependency rule**: arrows point inward. The domain knows nothing
about the application; the application knows nothing about
infrastructure; infrastructure knows about both.

This is enforced by ArchUnit at build time. See
`HexagonalArchitectureTest.java`.

---

## 2. Why three layers and not "just put it in a service"

The honest answer: a single-layer Spring service is fine for small
projects. This three-layer split earns its keep when:

- **The domain is non-trivial.** Anti-money laundering has dense rules
  and a vocabulary the business uses. Encoding those rules as plain
  Java methods on the aggregate makes them readable to compliance
  staff and trivially testable.
- **The codebase will live for years.** The PhD timeline is 3+ years.
  Any layering shortcut taken now will be paid for later when the
  framework version changes or a new persistence target appears.
- **Tests need to run fast.** A `Case` test instantiates a plain Java
  object — no Spring context, no database. The full domain test suite
  runs in seconds, which is what makes meaningful TDD sustainable.

If those three conditions don't apply, drop the layers. Cargo-culting
hexagonal architecture into a 200-line CRUD app is the kind of thing
junior engineers do to look senior.

---

## 3. Module breakdown

### `domain/`

The pure business model. **Allowed to import**: nothing outside `java.*`
and the `domain` package itself.

- `model/` — aggregates, entities, value objects, enums
- `event/` — domain events (sealed, exhaustive)
- `exception/` — domain-specific runtime exceptions

Whenever someone wants to add a `@Component` or `@Entity` here, the
ArchUnit test fails the build.

### `application/`

The orchestration layer. **Allowed to import**: `domain`, plus Spring
annotations for `@Service` / `@Transactional`, plus Micrometer for
observability.

- `command/` — input DTOs (records), one per use case
- `port/` — interfaces the domain needs the outside world to provide
- `CaseApplicationService.java` — the use cases themselves

This is **the only layer that imports both Spring and the domain**. It
is also where observability lives, because:

- Tracing is cross-cutting → application-layer concern
- Metrics depend on the use case being executed → application-layer
- The domain stays free of any of this

### `infrastructure/`

The adapters. **Allowed to import**: everything.

- `api/` — REST controllers, exception handlers
- `persistence/` — JPA entities and repository adapters
- `messaging/` — Kafka producers and consumers
- `config/` — Spring configuration classes
- `observability/` — bean definitions for Tracer, MeterRegistry, etc.

---

## 4. The dual-class trick: Case and CaseJpaEntity

`Case` is the aggregate. `CaseJpaEntity` is the persistence model.
They live in different packages and serve different purposes:

| | Domain `Case` | `CaseJpaEntity` |
|---|---|---|
| Location | `domain/model/` | `infrastructure/persistence/` |
| Purpose | Encode behaviour | Encode storage |
| Annotations | None | `@Entity`, `@Column`, `@Version` |
| Setters | No (immutable except via methods) | Package-private `apply()` |
| Used in unit tests | Yes (no DB needed) | No |

The `CaseRepositoryAdapter` translates between them. Cost: a small amount
of mapping code. Benefit: the domain is testable without a database, the
storage schema can evolve independently, and ORM quirks (lazy loading,
detached entities) cannot leak into business logic.

This is the **single most important thing** to keep correct. If a future
edit makes `Case` JPA-annotated, you've collapsed two layers into one
and lost the architectural benefit.

---

## 5. Domain events as the integration mechanism

Every state change on `Case` produces a domain event. The application
service then:

1. Persists the aggregate (transactional boundary)
2. Pulls events from the aggregate
3. Publishes them via `DomainEventPublisher`

The `KafkaDomainEventPublisher` adapter does two things at once:

```java
current.event(event.eventType());      // ← span event for traces
current.tag("domain.event.id", ...);   // ← span attribute
kafka.send(topic, key, event);          // ← business event to Kafka
```

This is where the **observability research design** is wired in. The
same event that triggers Reporting service to file a SAR also becomes a
span attribute in the trace. Anomaly-detection features and business
audit trails are produced by the same line of code.

> ✅ **Implemented as of v0.2.** Both Monitoring and Case Management
> now use the **transactional outbox pattern**: events are written to
> an `outbox` table in the same DB transaction as the aggregate, then
> a scheduled `OutboxDispatcher` polls and forwards to Kafka with
> `FOR UPDATE SKIP LOCKED` so multiple workers can run safely.
> Consumers dedup via the `processed_events` table to make
> at-least-once delivery effectively exactly-once.

---

## 6. The test pyramid

```
                  ┌─────────────────────┐
                  │  E2E (K8s, planned) │   ← few, slow
                  └─────────────────────┘
              ┌─────────────────────────────┐
              │  Contract (Pact, planned)   │
              └─────────────────────────────┘
          ┌─────────────────────────────────────┐
          │  Integration (Testcontainers)       │
          └─────────────────────────────────────┘
      ┌─────────────────────────────────────────────┐
      │  Application service (mocked ports)         │
      └─────────────────────────────────────────────┘
  ┌─────────────────────────────────────────────────────┐
  │  Domain (pure JUnit, jqwik, ArchUnit)               │   ← thousands, ms each
  └─────────────────────────────────────────────────────┘
```

**Mutation testing** (Pitest) is the senior signal. It runs the test
suite against deliberately-broken copies of the production code. If a
test still passes against a mutant, the test is too weak. The build
target requires ≥85% mutation coverage on `domain.*`.

---

## 7. Bounded context boundaries on the file system

The repository is a Maven multi-module project. Each bounded context
becomes one Maven module under `services/`:

```
services/
├── case-management/             ← this module
├── transaction-monitoring/      ← TBD
├── payment-initiation/          ← TBD
├── customer-kyc/                ← TBD
├── watchlist-screening/         ← TBD
└── regulatory-reporting/        ← TBD
```

Modules **do not depend on each other directly**. They communicate via:

- **Kafka topics** for asynchronous events
- **HTTP APIs** for synchronous queries
- **Pact contracts** to keep the inter-service interfaces stable

A potential `aml-shared` module is deliberately *not* introduced. Sharing
domain types between bounded contexts defeats the point of having
bounded contexts.

---

## 8. Deployment topology

Each service ships as:

- **One Docker image** (multi-stage build, JRE-only runtime)
- **One Helm chart** with `Deployment`, `Service`, `HPA`, `PDB`,
  `NetworkPolicy`, and `ServiceMonitor`
- **One Postgres database** (separate schema per service — never shared)
- **One or more Kafka topics** owned by the service

In Kubernetes:

- Namespace `aml` for application services
- Namespace `monitoring` for the observability stack
- Namespace `data` for stateful services (Postgres, Kafka, Redis)

`NetworkPolicy` resources restrict pod-to-pod communication so that
services can only reach the dependencies they actually need. This
matters for both production hygiene and for the research story —
intentional anomaly injection (chaos-mesh) needs a baseline that isn't
already noisy with unrelated traffic.

---

## 9. Decisions that look weird but are deliberate

### `CaseStatus` is an enum, not a class hierarchy

A `sealed class CaseStatus` with subclasses for each state would let us
attach behaviour per state. We don't, because the state-specific
behaviour is small (just allowed transitions) and an enum is far simpler
to persist and serialise. If state-specific behaviour grows beyond a few
methods, this decision should be revisited.

### Domain events are `record` classes, not POJOs

Records give equality by value, immutability, and concise syntax. They
serialise cleanly to JSON. The only constraint is that the JSON
deserializer needs the canonical constructor — which is the default for
Jackson 2.12+.

### The repository port returns `Optional<Case>`, not `Case`

Returning `null` would be the alternative. Optional is more honest about
"this might not exist" and makes the application service's behaviour
explicit (`.orElseThrow(CaseNotFoundException::new)`).

### `riskScore` is `int`, not `BigDecimal`

It's a 0..100 integer score from a rule engine. It is not money. Using
`BigDecimal` for it would be performative.

---

*This document evolves with the code. When you change architecture,
update here.*

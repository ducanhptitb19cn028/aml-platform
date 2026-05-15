# AML Platform — Microservices Testbed for Observability Research

[![ci](https://github.com/USERNAME/aml-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/USERNAME/aml-platform/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A production-grade, **Domain-Driven Design** + **Test-Driven Development**
AML platform built with Spring Boot 3 and deployed on Kubernetes. **Three
services** with **two contract types** between them — async Kafka events
(Pact-locked) AND synchronous HTTP queries (Pact-locked) — plus
**transactional outbox** on producer sides and **processed-events
idempotency** on consumer sides. Serves a dual purpose:

1. **Senior engineering reference** — strict hexagonal architecture,
   full Helm deployment, comprehensive test pyramid (unit / property /
   architecture / mutation / integration / contract).
2. **Research testbed** — instrumented end-to-end with OpenTelemetry as
   a measurement instrument for observability-driven anomaly detection
   in cloud-native distributed systems.

> 📖 [`docs/BUSINESS_LOGIC.md`](docs/BUSINESS_LOGIC.md) — domain models,
> bounded-context map, ubiquitous language, every business rule.
>
> 📖 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — how the code is
> structured and why.
>
> 📖 [`docs/research-design.md`](docs/research-design.md) — how the
> platform serves the PhD research questions.

---

## What's in here

Two real services with a real contract between them:

```
                      AlertRaised event
                       (Kafka, Pact-locked)
                              │
   ┌──────────────────────────┼──────────────────────────┐
   │                          ▼                          │
   ▼                                                     ▼
┌──────────────────────────┐              ┌──────────────────────────┐
│ Transaction Monitoring   │              │   Case Management        │
│                          │              │                          │
│  Rule engine (4 rules)   │              │  Case aggregate          │
│  Specification pattern   │              │  Lifecycle state machine │
│  Property-based tests    │              │  Investigator workflow   │
│  Pitest mutation tests   │              │  Pitest mutation tests   │
│                          │              │                          │
│  Producer of AlertRaised │              │  Consumer of AlertRaised │
└──────────────────────────┘              └──────────────────────────┘
```

| Service | Aggregate(s) | Status |
|---------|--------------|--------|
| Case Management | `Case` | ✅ done |
| Transaction Monitoring | `Transaction`, `Alert` | ✅ done |
| Customer / KYC | `Customer` | ✅ done |
| Watchlist Screening | `ScreeningResult` | planned |
| Payment Initiation | `Payment` | planned |
| Regulatory Reporting | `Report` (CQRS read side) | planned |

---

## Quickstart

### 1. Run the test suite (no infra needed)

```bash
./mvnw verify
```

Pure domain tests (unit + property + ArchUnit) run in seconds with no
Docker, no database. That speed is the payoff of strict hexagonal
architecture.

### 2. Run the contract test pipeline

```bash
make contract
```

This generates a Pact file from Case Management's consumer expectations,
then verifies that Transaction Monitoring's producer matches.

### 3. Run both services locally

```bash
make infra-up                          # Postgres x2, Kafka, Prom, Tempo, Loki
make build                             # compile both services

# In one terminal — Transaction Monitoring on :8081
cd services/transaction-monitoring && ../../mvnw spring-boot:run

# In another terminal — Case Management on :8080
cd services/case-management && ../../mvnw spring-boot:run
```

Now exercise the end-to-end flow:

```bash
# 1. Submit a transaction that triggers a rule
curl -X POST http://localhost:8081/api/v1/transactions/evaluate \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId":"cust-1",
    "amount":"15000",
    "currency":"GBP",
    "originCountry":"GB",
    "destinationCountry":"IR",
    "channel":"SWIFT"
  }'

# 2. Monitoring evaluates rules, raises an Alert, publishes to Kafka.
# 3. Case Management's listener picks it up and opens a Case automatically.
# 4. Open Grafana → Tempo and follow the trace from one service to the next.

open http://localhost:3000   # Grafana → Explore → Tempo
```

### 4. Deploy to local Kubernetes

```bash
make cluster-up                        # start local registry + observability
make image-cases image-monitoring
make deploy
```

---

## Repository layout

```
aml-platform/
├── pom.xml                              ← parent POM (multi-module)
├── Makefile                             ← every command you need
│
├── services/
│   ├── case-management/                 ← Service 1
│   │   ├── pom.xml
│   │   ├── Dockerfile
│   │   └── src/
│   │       ├── main/java/.../domain/         ← pure DDD, no Spring
│   │       ├── main/java/.../application/    ← use cases + obs hooks
│   │       └── main/java/.../infrastructure/ ← REST, JPA, Kafka adapters
│   │           └── messaging/AlertRaisedListener.java   ← consumes from Monitoring
│   │
│   └── transaction-monitoring/          ← Service 2
│       ├── pom.xml
│       ├── Dockerfile
│       └── src/
│           ├── main/java/.../domain/
│           │   ├── model/                ← Transaction, Alert, Money, Country
│           │   └── rule/                 ← 4 rules + RuleEngine + Specification
│           ├── main/java/.../application/
│           └── main/java/.../infrastructure/
│
├── infrastructure/
│   ├── docker-compose.yml               ← 2 Postgres, Kafka, Prom, Tempo, Loki
│   ├── helm/
│   │   ├── case-management/             ← Helm chart for service 1
│   │   └── transaction-monitoring/      ← Helm chart for service 2
│   └── observability/                   ← Prom + Tempo configs
│
├── docs/
│   ├── BUSINESS_LOGIC.md                ← domain rules for both services
│   ├── ARCHITECTURE.md
│   └── research-design.md
│
└── .github/workflows/ci.yml             ← build + contract + integration + docker
```

---

## Test pyramid — what each layer proves

| Layer | Tool | Speed | What it proves |
|-------|------|-------|----------------|
| Unit | JUnit + AssertJ | ms | Aggregate behaviour, invariants |
| Property | jqwik | ms | Invariants under random input — engine combiner properties |
| Architecture | ArchUnit | ms | Hexagonal boundaries hold; no Spring in domain |
| Mutation | Pitest | s | Tests catch real bugs (≥ 85% coverage on domain) |
| Integration | Testcontainers | s | JPA + Kafka actually work |
| **Contract** | **Pact** | **s** | **Cross-service event schema is stable** |
| End-to-end | Docker Desktop K8s + k6 (planned) | min | Full cluster behaviour |

`make verify` runs the full quality gate.

---

## The Pact contract — the senior signal

The two services communicate via the `AlertRaised` event. Its schema
is locked by Pact tests on **both sides**:

- Consumer side (Case Management) declares: "I expect the event to look
  like this" → generates a `pact.json`.
- Producer side (Transaction Monitoring) verifies: "I can produce
  exactly this shape" → reads the `pact.json` and replays.

If a future PR in Monitoring would break this contract, the producer's
CI fails — before integration, before staging, before production.
This is what makes microservices safe to evolve independently.

---

## Why this exists

Most observability research uses toy benchmarks (DeathStarBench,
TrainTicket) or anonymised production traces. This platform is a third
option: a **domain-rich**, controllable testbed where:

- The state machines of every aggregate give **deterministic ground
  truth** for "normal behaviour"
- Domain events are emitted as both Kafka messages and OpenTelemetry
  span events — **one mechanism, two consumers**
- Anomalies can be **injected with precise causal labels** for
  evaluation
- Cross-service causal flows give the detection model real propagation
  paths to learn from

See [`docs/research-design.md`](docs/research-design.md).

---

## License

MIT — see [LICENSE](LICENSE).

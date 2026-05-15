# Business Logic — AML Platform

This document explains **what the system does, why, and in whose words**.
It is the bridge between the regulatory domain (Anti-Money Laundering)
and the code, so that a developer joining the project can read this
once and understand every decision the domain models make.

> If you want to know *how* the code is structured, read
> [`ARCHITECTURE.md`](ARCHITECTURE.md).
> If you want to know *why this exists for research*, read
> [`research-design.md`](research-design.md).
> This document is about **the business**.

---

## 1. The regulatory context

Banks operating in the UK and EU are required by law to detect and report
financial crime — primarily money laundering and terrorist financing. The
relevant legislation includes the UK *Proceeds of Crime Act 2002*, the
*Money Laundering Regulations 2017*, and the EU *6th Anti-Money Laundering
Directive (6AMLD)*.

In practice this means every regulated bank must:

1. **Know its customers** (KYC) — verify identity at onboarding and
   periodically thereafter.
2. **Screen** customers and transactions against sanctions lists (OFAC,
   UN, HMT) and Politically Exposed Persons (PEP) lists.
3. **Monitor transactions** in real time and produce alerts when
   patterns look suspicious.
4. **Investigate** every alert through a controlled, auditable workflow.
5. **Report** confirmed suspicions to the regulator within statutory
   deadlines, via Suspicious Activity Reports (SARs in the UK, STRs in
   most other jurisdictions).

This platform implements steps 3, 4, and 5. Today **Transaction
Monitoring** (step 3) and **Case Management** (step 4) are the two
implemented bounded contexts.

---

## 2. Bounded contexts and the platform map

| Context | Aggregate root(s) | Responsibility | Status |
|---------|-------------------|----------------|--------|
| **Customer / KYC** | `Customer` | Identity, risk profile, ongoing due diligence | planned |
| **Watchlist Screening** | `ScreeningResult` | Sanctions / PEP / adverse-media checks | planned |
| **Payment Initiation** | `Payment` | Capture and authorise outbound payments | planned |
| **Transaction Monitoring** | `Transaction`, `Alert` | Apply rules to transactions, raise alerts | ✅ done |
| **Case Management** | `Case` | Investigator workflow over alerts | ✅ done |
| **Regulatory Reporting** | `Report` | Generate SAR/STR submissions | planned |

### The flow between the two implemented contexts

```
   Customer makes a payment
            │
            ▼
   Transaction Monitoring evaluates rules           ← SERVICE 1
            │
            ▼ (if rules fire)
   AlertRaised ───── Kafka ─────► Case Management   ← SERVICE 2
                                       │
                                       ▼
                          Investigator works the case
                                       │
                       ┌───────────────┴───────────────┐
                       ▼                               ▼
                 Case is closed               Case is escalated
                 (false positive)             (genuine suspicion)
                                                       │
                                                       ▼
                                              Reporting files SAR
```

The arrow labelled "Kafka" carries the `AlertRaised` event. Its schema
is **the inter-service contract** — locked down by Pact tests on both
sides. See `services/case-management/src/test/.../contract/` and
`services/transaction-monitoring/src/test/.../contract/`.

---

## 3. The Transaction Monitoring context

### 3.1 What this service does

> Transaction Monitoring observes every transaction the bank processes,
> evaluates it against a set of detection rules, and raises an Alert
> whenever the combined risk score warrants human review.

It owns two aggregates: `Transaction` (read-only after observation) and
`Alert` (created when rules fire). It does **not** own the lifecycle of
either an Alert or a Case past the moment of raising — that responsibility
moves to Case Management.

### 3.2 The Transaction aggregate

A `Transaction` carries:

| Field | Meaning |
|-------|---------|
| `id` | UUID, generated at observation time |
| `customerId` | Identifier of the bank customer |
| `amount` | Money value object (decimal + ISO currency) |
| `originCountry` | ISO 3166-1 alpha-2 source country |
| `destinationCountry` | ISO 3166-1 alpha-2 destination country |
| `channel` | How the payment was initiated: `SEPA`, `FASTER_PAYMENTS`, `SWIFT`, `CARD`, `CASH_DEPOSIT`, `CRYPTO_OFFRAMP` |
| `occurredAt` | When the transaction took place |

The aggregate is **immutable after construction**. Rule evaluation reads
it; nothing in this context mutates it. That's what makes parallel rule
evaluation safe and what lets us treat a transaction stream as
deterministic ground truth for the research pipeline.

`Money` is its own value object, not a `double` or a `long` of pence.
Two reasons: (a) financial regulation requires deterministic decimal
arithmetic, and (b) cross-currency comparisons should be impossible
without explicit conversion. `Money.of("1000", "GBP").isGreaterThanOrEqual(Money.of("1000", "USD"))`
throws — it does not silently lie.

`CountryCode` enforces ISO 3166-1 alpha-2 format and exposes
`isHighRisk()` for the FATF jurisdiction lists.

### 3.3 The rule engine — the heart of this service

The engine is the **Specification pattern** in textbook form. Each rule
is a pure function from `RuleContext` (the transaction plus the
customer's recent history) to `RuleVerdict` (fired? what risk score?
why?).

Four rules are implemented out of the box. Each is an actual AML
detection pattern, not a toy.

#### AML-101 — High Value Transaction (`HighValueRule`)

> Triggers when a single transaction equals or exceeds a threshold
> (default £10,000).

Risk contribution scales linearly from 40 (at the threshold) up to a
cap of 80 (at five times the threshold). A single high-value transaction
on its own is rarely escalated — it usually combines with another
signal to push the combined score into alert territory.

This rule maps directly to the FinCEN Currency Transaction Report
threshold and the equivalent HMRC reporting trigger.

#### AML-202 — Velocity (`VelocityRule`)

> Triggers when a customer makes more than `maxTransactions`
> transactions within a sliding `window` ending at the current
> transaction's time (default: more than 5 in 1 hour).

Velocity is a classic **mule account** indicator. An account being
used as a pass-through to break a clear audit trail typically shows
sudden bursts of activity that are inconsistent with the account's
own historical pattern.

#### AML-303 — Structuring (`StructuringRule`)

> Triggers when a customer makes multiple sub-threshold transactions
> within a window whose sum equals or exceeds the high-value threshold,
> with at least `minTransactions` (default 3) contributing to the sum.

Structuring (sometimes called "smurfing") is **deliberate evasion**:
instead of one £15k transfer that would file a CTR, the customer makes
three £6k transfers a day apart. Each is below the reporting threshold
individually, but in aggregate they cross it.

The rule explicitly excludes transactions that are themselves above
the threshold — those are HighValueRule's territory. This is an
example of **rule responsibility separation**: each rule answers one
question, cleanly.

#### AML-404 — High-Risk Corridor (`HighRiskCorridorRule`)

> Triggers when either the origin or destination country is on the
> FATF high-risk jurisdictions list (Iran, North Korea, Myanmar,
> Afghanistan, Syria, etc.).

This rule's risk contribution is intentionally modest (35) so it
rarely fires alone. It functions as a **risk multiplier**: a transaction
that would otherwise be borderline gets pushed over the threshold by
the corridor signal.

### 3.4 How the engine combines rule verdicts

Multiple rules can fire on the same transaction. Combining them is
non-trivial — naïvely adding scores would let two rules each
contributing 60 produce 120, which makes no sense in a 0..100 scale.

The combiner uses **diminishing returns**:

```
combined = 1 - product over fired rules of (1 - contribution/100)
```

Properties of this formula:

- Returns 0 when no rules fire
- Approaches but never exceeds 100
- Is monotonic — adding a fired rule never decreases the score
- Two 50% contributions combine to 75%, not 100%
- Two 80% contributions combine to 96%

These properties are enforced by **property-based tests** in
`RuleEngineProperties` using jqwik. The tests run against thousands of
randomly-generated inputs, not just the examples we hand-wrote.

### 3.5 The Alert aggregate

When the engine returns an `EngineResult` with at least one fired rule,
the application service raises an `Alert`:

| Field | Meaning |
|-------|---------|
| `id` | UUID |
| `transactionId` | The transaction that triggered the alert |
| `customerId` | Inherited from the transaction |
| `riskScore` | The combined score from the engine |
| `firedRuleIds` | Which rules contributed (e.g. `["AML-101", "AML-404"]`) |
| `rationale` | Concatenated rule rationales — the audit record |
| `raisedAt` | Timestamp |

The Alert is **immutable after creation in this context**. Its further
lifecycle (assigned to investigator, escalated, closed) belongs to the
Case Management bounded context. That is the bounded-context boundary
in action: each context owns the parts of the lifecycle relevant to
its responsibility.

Raising an Alert publishes an `AlertRaised` event to Kafka topic
`aml.alerts.events`. This is the cross-service contract event.

### 3.6 Invariants in this context

| # | Invariant | Where enforced |
|---|-----------|----------------|
| TM-1 | `Transaction.amount` is non-negative | `Money` constructor |
| TM-2 | Currencies are never silently coerced | `Money.requireSameCurrency` |
| TM-3 | Country codes are valid ISO 3166-1 alpha-2 | `CountryCode` constructor |
| TM-4 | `RiskScore` (per rule) is in `0..100` | `RuleVerdict` constructor |
| TM-5 | Combined engine score is in `0..100` | `RuleEngine.combine` (property-tested) |
| TM-6 | Adding a fired rule never decreases the combined score | `RuleEngine.combine` (property-tested) |
| TM-7 | Fired verdicts must carry a non-blank rationale | `RuleVerdict` constructor |
| TM-8 | An Alert can only be raised when at least one rule fired | `Alert.raise` factory |
| TM-9 | The rule engine refuses to operate without at least one rule | `RuleEngine` constructor |

---

## 4. The Case Management context

### 4.1 What a case is

> A **case** is a structured record of a suspected money-laundering
> incident, opened in response to one alert and worked by one
> investigator until it reaches a definitive resolution.

A case is the **unit of accountability** in compliance. Every alert
either becomes a case or is dismissed at the alerting layer; once a
case exists, it must be resolved with a written rationale that
survives audit.

### 4.2 What's inside a case

| Field | Meaning |
|-------|---------|
| `id` | UUID, generated on creation |
| `alertId` | The upstream alert (received from Monitoring's `AlertRaised` event) |
| `customerId` | The customer under investigation |
| `riskScore` | Numeric risk indicator, 0..100, inherited from the alert |
| `status` | Lifecycle position (see §4.3) |
| `assignedInvestigator` | Who is currently working it |
| `openedAt` | Immutable creation timestamp |
| `lastUpdatedAt` | Most recent state change |

The risk score is bucketed into **risk bands** (`LOW < 30`,
`MEDIUM 30..69`, `HIGH ≥ 70`) used by case assignment policy and as a
low-cardinality observability tag.

### 4.3 The case lifecycle

```
                  ┌──────────────────────────────────────┐
                  │                                      │
                  │           ┌──────────────────────┐   │
                  │           │                      ▼   ▼
   ┌────────┐    ┌─▼─────────────────────┐    ┌─────────────┐
   │  OPEN  │───►│ UNDER_INVESTIGATION   │───►│  ESCALATED  │
   └───┬────┘    └────────┬──────────────┘    └──────┬──────┘
       │                  │       ▲                  │
       │                  │       │                  │
       │                  ▼       │                  │
       │           ┌────────────────┐                │
       │           │ PENDING_REVIEW │◄───────────────┘
       │           └────────┬───────┘
       │                    │
       ▼                    ▼
                       ┌────────┐
   ─────────────────►  │ CLOSED │  (terminal)
                       └────────┘
```

#### `OPEN`
Just created from an alert. No investigator yet. Can move to
`UNDER_INVESTIGATION` (when assigned) or directly to `CLOSED` (triage
determines false positive without investigation).

#### `UNDER_INVESTIGATION`
An investigator owns the case and is actively working it.

#### `ESCALATED`
Investigator found enough evidence that they cannot resolve at their
level. Escalation goes to a senior compliance officer or to the MLRO
(Money Laundering Reporting Officer), who has statutory authority to
file a SAR. Escalation always carries a written `reason`.

#### `PENDING_REVIEW`
Awaiting MLRO sign-off or peer review. Can return to
`UNDER_INVESTIGATION` (more work needed) or move to `CLOSED`.

#### `CLOSED`
Terminal. Common resolutions: `"false positive"`, `"SAR filed"`,
`"customer exited"`, `"insufficient evidence"`. **A closed case cannot
be reopened.** New evidence triggers a new case that references the
old one — preserving the audit trail is a regulatory requirement.

### 4.4 The behaviours a case supports

#### `Case.open(alertId, customerId, riskScore)` — factory
Creates a case in `OPEN` status. Validates non-blank IDs and
`riskScore` in `0..100`. Raises `CaseOpened`.

#### `assignTo(investigatorId)`
Refuses on `CLOSED`. From `OPEN`, transitions to `UNDER_INVESTIGATION`.
Otherwise just changes the assignee. Raises `CaseAssigned`.

#### `escalate(reason)`
Only legal from `UNDER_INVESTIGATION`. Requires non-blank reason.
Transitions to `ESCALATED`. Raises `CaseEscalated`.

> **Why can't you escalate directly from `OPEN`?** The regulator
> requires a documented investigative trail before escalation. An
> investigator must touch the case before escalating it.

#### `close(resolution)`
Legal from any non-terminal state. Requires non-blank resolution.
Transitions to `CLOSED`. Raises `CaseClosed`.

### 4.5 Domain events from this context

| Event | When | Carries |
|-------|------|---------|
| `CaseOpened` | A new case is created | caseId, alertId, customerId, riskScore |
| `CaseAssigned` | An investigator is assigned | caseId, investigatorId |
| `CaseEscalated` | The case is escalated | caseId, reason |
| `CaseClosed` | The case reaches terminal state | caseId, resolution |

These are published to topic `aml.cases.events` and consumed by
Reporting (for SAR generation) and Customer/KYC (for risk profile
updates).

### 4.6 Invariants in this context

| # | Invariant | Where enforced |
|---|-----------|----------------|
| CM-1 | Every case has non-null `id`, `alertId`, `customerId`, `riskScore`, `status`, `openedAt` | `Case` constructor |
| CM-2 | `riskScore` is always `0..100` | `RiskScore` value object |
| CM-3 | A case starts in `OPEN` status | `Case.open()` factory |
| CM-4 | State transitions follow §4.3. Anything else throws `IllegalCaseTransitionException` | `CaseStatus.canTransitionTo` |
| CM-5 | A `CLOSED` case is terminal | `CaseStatus.allowedNext = empty` |
| CM-6 | Escalation always records a non-blank `reason` | `Case.escalate()` |
| CM-7 | Closure always records a non-blank `resolution` | `Case.close()` |
| CM-8 | Assignment requires a non-blank `investigatorId` | `Case.assignTo()` |
| CM-9 | Every state change produces at least one domain event | All behaviour methods |

---

## 5. Ubiquitous language

The terms below appear in code, in conversations with compliance staff,
and in this document. **Use these words exactly** — drift in vocabulary
is the most common cause of model rot in DDD codebases.

| Term | Meaning |
|------|---------|
| **Transaction** | A movement of money the bank processes. Lives in the Monitoring context as a read-only fact. |
| **Channel** | The mechanism of a transaction (SEPA, Faster Payments, SWIFT, card, cash deposit, crypto off-ramp). |
| **Rule** | A specification that examines a transaction in context and decides whether it looks suspicious. |
| **Rule verdict** | The result of one rule evaluating one transaction — fired or not, plus a numeric risk contribution and a textual rationale. |
| **Risk score** | A 0..100 value. May refer to a single rule's contribution or to the engine's combined score. |
| **Risk band** | Categorical: `LOW`, `MEDIUM`, `HIGH`. |
| **Alert** | A signal raised when the rule engine decides a transaction warrants human review. Lives in Monitoring; consumed by Case Management. |
| **Case** | A workflow record opened in response to an alert. Unit of work for an investigator. |
| **Investigator** | A compliance analyst who works cases. Identified by `investigatorId`. |
| **MLRO** | Money Laundering Reporting Officer — bank employee with statutory authority to file SARs. Cases are escalated *to* the MLRO. |
| **Escalation** | Moving a case from an investigator's queue to a senior reviewer's, with a written reason. |
| **Resolution** | Free-text outcome recorded when a case closes. Required for audit. |
| **SAR / STR** | Suspicious Activity Report (UK) / Suspicious Transaction Report (international). Filed with the regulator on confirmed suspicion. |
| **KYC** | Know Your Customer — identity verification at onboarding and periodically thereafter. |
| **PEP** | Politically Exposed Person — higher-risk for corruption-related laundering. |
| **Sanctions list** | Government-published list of restricted individuals/entities (OFAC, HMT, UN, EU). |
| **Watchlist** | Umbrella term for sanctions, PEP, and adverse-media lists. |
| **Structuring / smurfing** | Deliberate evasion: splitting one large transaction into multiple sub-threshold ones to avoid reporting triggers. |
| **Velocity** | Burst of transactions over a short window — typical mule-account signal. |
| **Corridor** | The (origin, destination) country pair of a transaction. Used for geographic risk assessment. |
| **False positive** | An alert that fires according to a rule but does not represent actual suspicious activity. The most common case resolution. |

---

## 6. The cross-service contract

`AlertRaised` is the inter-service event that crosses the Monitoring →
Case Management boundary. Its schema is **the contract**, locked by
Pact tests on both sides:

```json
{
  "eventId":      "uuid",
  "occurredAt":   "ISO-8601 timestamp",
  "alertId":      { "value": "uuid" },
  "transactionId":{ "value": "uuid" },
  "customerId":   "string",
  "riskScore":    "integer 0..100",
  "firedRuleIds": ["string", ...],
  "rationale":    "string",
  "eventType":    "alert.raised",
  "aggregateId":  "string"
}
```

**Field changes that are safe** (additive, consumer ignores unknowns):
adding new optional fields.

**Field changes that are NOT safe** (breaking the contract): renaming
any field, changing a type, removing a field, tightening validation
beyond what consumers can tolerate.

The Pact test on the consumer side declares "this is what we expect";
the producer side asserts "we can produce exactly this." If a future
change in Monitoring would break this expectation, the build fails on
the producer's CI run — long before integration.

---

## 7. Messaging guarantees — the production-correctness story

Money laundering is a regulated domain. Losing an alert (a `CaseEscalated`
event that never reaches Reporting) is a regulatory incident. Duplicating
a case (the same alert opening two cases for two investigators) is an
operational incident. The platform addresses both rigorously.

### 7.1 Producer side — transactional outbox

When the application service saves an aggregate and publishes events,
both writes go to the **same database** in the **same transaction**:

1. The aggregate is persisted (`cases` or `alerts` table).
2. Each domain event is written as a row in the `outbox` table.

Either both succeed or both roll back. There is no scenario where an
event reaches Kafka without the corresponding aggregate state being
durable, and no scenario where an aggregate is persisted without its
event being eventually published.

A separate scheduled worker (`OutboxDispatcher`) reads pending outbox
rows, sends them to Kafka, and marks them dispatched. The worker uses
`FOR UPDATE SKIP LOCKED` so multiple instances run safely without
fighting over the same row. If the worker crashes between Kafka send
and DB mark, the row stays pending and gets resent — at-least-once
delivery, by design.

### 7.2 Consumer side — idempotency via processed_events

Because producers are at-least-once, consumers must be idempotent.
Every inbound event carries a unique `eventId`. Before processing, the
listener calls:

```sql
INSERT INTO processed_events (event_id, processor)
VALUES (?, ?)
ON CONFLICT DO NOTHING
```

If the insert affects 1 row, this is a new event — proceed. If it
affects 0 rows, we've seen it before — skip. The dedup check and the
business action (e.g. opening a Case) run inside the same DB
transaction, so a race between two listener threads cannot result in
two cases.

### 7.3 What this gives us, in business terms

- **No silently lost alerts.** Every `AlertRaised` event will be
  delivered to Case Management at least once, eventually.
- **No duplicate cases.** Every `AlertRaised` event will result in
  exactly one `Case`, no matter how many times Kafka redelivers.
- **No phantom records.** No event will be observable downstream
  unless its originating aggregate also exists in the producer's DB.

These three properties together give us **effectively-exactly-once
business semantics** on top of Kafka's at-least-once mechanical
guarantee.

---

## 8. Why this is also a research artefact

The same domain events that drive the business workflow also drive the
PhD research pipeline. Three properties of the model make this possible:

**Property 1 — Behavioural ground truth.** Both contexts have
deterministic state machines (or read-only aggregates). Given a
sequence of inputs, the outputs are uniquely defined. This gives the
anomaly-detection model a clean baseline of "what each service should
be doing."

**Property 2 — Semantic events.** A `CaseEscalated{reason="structuring"}`
event is qualitatively richer than HTTP 200 / 250ms. The model gets
business-meaningful labels.

**Property 3 — Causal traces.** Trace context propagates from the
upstream transaction evaluation, into the `AlertRaised` Kafka message,
into the case-creation request, and onward into the case's own events.
A single causal chain spans both services. The detection model can
reason over that chain.

The research-side details are in [`research-design.md`](research-design.md).

---

## 9. Pointers into the code

| Concept | File |
|---------|------|
| Case aggregate | `services/case-management/src/main/java/.../domain/model/Case.java` |
| Case lifecycle | `services/case-management/src/main/java/.../domain/model/CaseStatus.java` |
| Transaction aggregate | `services/transaction-monitoring/src/main/java/.../domain/model/Transaction.java` |
| Alert aggregate | `services/transaction-monitoring/src/main/java/.../domain/model/Alert.java` |
| Rule interface | `services/transaction-monitoring/src/main/java/.../domain/rule/Rule.java` |
| Rule engine | `services/transaction-monitoring/src/main/java/.../domain/rule/RuleEngine.java` |
| The four rules | `services/transaction-monitoring/src/main/java/.../domain/rule/*Rule.java` |
| Cross-service event | `services/transaction-monitoring/src/main/java/.../domain/event/AlertRaised.java` |
| Inbound Kafka listener | `services/case-management/src/main/java/.../infrastructure/messaging/AlertRaisedListener.java` |
| Pact consumer test | `services/case-management/src/test/java/.../contract/AlertRaisedConsumerPactTest.java` |
| Pact provider test | `services/transaction-monitoring/src/test/java/.../contract/AlertRaisedProducerPactTest.java` |
| Outbox table (Monitoring) | `services/transaction-monitoring/src/main/resources/db/migration/V2__outbox.sql` |
| Outbox dispatcher | `services/transaction-monitoring/src/main/java/.../infrastructure/messaging/OutboxDispatcher.java` |
| processed_events table | `services/case-management/src/main/resources/db/migration/V2__idempotency.sql` |
| Idempotent listener | `services/case-management/src/main/java/.../infrastructure/messaging/AlertRaisedListener.java` |

---

## 10. Glossary of design decisions

| Question | Short answer |
|----------|-------------|
| Why are domain aggregates not JPA entities? | So the domain has zero framework dependencies and runs in unit tests in milliseconds. JPA entities are separate adapter classes. |
| Why is `Money` a value object instead of `BigDecimal`? | So the currency invariant lives in one place and cross-currency comparisons throw. |
| Why is the rule engine the Specification pattern? | Because rules need to be composable, individually testable, and pure functions of context. Specification is the textbook fit. |
| Why does the engine combine scores with diminishing returns? | Adding raw scores would overflow 100 and lose meaning. The 1−product formula stays in [0,100], is monotonic, and rewards multiple weak signals. |
| Why publish AlertRaised to Kafka via an outbox instead of synchronously? | Decoupling, durability, and atomic-with-DB-commit. The outbox ensures the event and the aggregate state always agree. |
| Why dedup on the consumer with processed_events? | Outbox + Kafka give us at-least-once delivery. Idempotent consumers turn that into effectively-exactly-once business semantics. |
| Why don't we share Java types between services? | Sharing types couples the consumer to the producer's internal model. The contract is the message shape, not a JAR. |
| Why are domain events sealed interfaces? | Exhaustive pattern matching at compile time. Adding a new event without handling it is a build error. |

---

*Last updated: see `git log -- docs/BUSINESS_LOGIC.md`.*

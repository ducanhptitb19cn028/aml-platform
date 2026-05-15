# Research Design — Observability-Driven Intelligence for Anomaly Detection

This document explains how the AML microservices platform serves as the
**testbed** for the PhD research, and what design decisions in the
platform exist *because of* the research questions.

> If you want to know the domain, read [`BUSINESS_LOGIC.md`](BUSINESS_LOGIC.md).
> If you want to know the code structure, read [`ARCHITECTURE.md`](ARCHITECTURE.md).
> This document is about **the research**.

---

## 1. The platform-as-testbed approach

A common weakness in observability research is the testbed itself. Most
papers use one of:

- **Toy benchmarks** like DeathStarBench or TrainTicket — low semantic
  meaning, synthetic workloads, easy to overfit detectors to.
- **Anonymised production traces** — no ground truth, no ability to
  inject controlled anomalies, no semantic labels.
- **Hand-coded synthetic services** — small enough to implement,
  unrealistic enough that reviewers raise eyebrows.

This work uses a **fourth option**: a domain-rich production-grade
platform that the researcher controls end-to-end. The AML domain is
chosen because:

1. **State machines with clear invariants** (case lifecycle) provide
   deterministic ground truth for normal behaviour.
2. **Multi-service causal flows** (alert → case → escalation → report)
   allow study of cross-service anomaly propagation.
3. **Heterogeneous workload** (sync API calls, async Kafka events,
   batch reporting) exercises every observability primitive.
4. **Realistic complexity** — banking domain language, regulatory
   constraints, and failure modes that reviewers recognise as
   non-trivial.

---

## 2. Research questions and the platform features that answer them

### RQ1 — Signal sufficiency

> Which combination of metrics, logs, and traces minimises detection
> latency and false-positive rate for service-level anomalies?

**Platform features required:**
- All three signals captured at full fidelity
- Trace context propagated through Kafka so async flows are causally
  linkable
- Histogram-based metrics (not just summaries) so quantile-aware
  features are available to the detector
- Structured logs carrying `traceId`/`spanId` for post-hoc joining

**How the platform delivers:**
- `application.yml`: `tracing.sampling.probability: 1.0` for full
  fidelity
- `logback-spring.xml`: `LogstashEncoder` with MDC trace correlation
- `CaseApplicationService`: every span tagged with business attributes
  (`risk.band`, `case.id`, `customer.id`) — these are the features

### RQ2 — LLM-driven RCA vs statistical baselines

> Can LLM-driven root cause analysis outperform threshold/statistical
> baselines on multi-service incidents?

**Platform features required:**
- A **service graph** that the LLM can reason over
- **Domain events** that carry semantic meaning the LLM can interpret
- Ability to **inject controlled anomalies** with known root causes for
  evaluation

**How the platform delivers:**
- Domain events are first-class and propagated to Kafka with stable
  schemas
- Each service exposes its dependency graph via OpenTelemetry resource
  attributes
- Chaos Mesh integration for fault injection (network latency, packet
  loss, pod kills) targeted at specific service edges

### RQ3 — Observability overhead

> What is the true performance overhead of full-fidelity tracing for
> regulated workloads, and how does it scale with service count?

**Platform features required:**
- Ability to run identical workloads with tracing on/off/sampled
- Stable, repeatable load generation
- Resource accounting at the pod level

**How the platform delivers:**
- The `TRACE_SAMPLE_RATE` env var switches between `1.0`, `0.1`, `0.0`
  without a redeploy
- k6 load tests with identical scenarios across profiles
- Vertical pod autoscaler in observe-only mode records resource usage

---

## 3. The instrumentation pattern — why domain events are the bridge

The single most important design decision: **domain events serve as both
the business event stream AND the high-fidelity behavioural signal for
the detection model.**

```
        Domain (Case aggregate)
                 │
                 ▼
        DomainEvent (e.g. CaseEscalated)
                 │
        ┌────────┴────────┐
        ▼                 ▼
    Kafka topic       OTel span event
    (business)        (research signal)
```

This means:

- **No double instrumentation** — one event, two consumers
- **Ground truth labels are free** — `case.escalated{reason="SAR filed"}`
  events are positive examples for the detection model
- **Causal links are preserved** — the trace context attached to the
  Kafka message lets us reconstruct cross-service incident graphs

This is the platform's **main novel methodological contribution**:
*observability is not a side-effect of operations, it is a designed
artefact of the domain layer*.

---

## 4. Experimental protocol

### Phase 1 — Baseline characterisation

1. Run synthetic AML workload at steady state for 24 hours
2. Capture full RED + USE metrics, all logs, all traces
3. Compute baseline distributions for every signal
4. Establish SLO thresholds from observed P99s

### Phase 2 — Controlled anomaly injection

For each anomaly class, inject via Chaos Mesh and record T0 (injection
time) and Td (first detection time) for each detector under test:

- **Latency anomalies** — Postgres slow query, Kafka lag
- **Resource anomalies** — CPU starvation, memory pressure, disk full
- **Functional anomalies** — rule engine misclassification, schema drift
- **Causal anomalies** — Customer service down → KYC checks fail →
  cases pile up in Case Management with malformed risk scores

### Phase 3 — Detector comparison

Detectors evaluated:

1. SLO burn-rate (statistical baseline)
2. Multivariate Isolation Forest on metric features
3. Trace-based anomaly detection (TraceAnomaly-style)
4. **Proposed**: multi-signal fusion with LLM-RCA over the service graph

Metrics: precision, recall, F1, mean detection latency, false-positive
rate per hour, root-cause accuracy.

### Phase 4 — Overhead study

Same workload, three tracing profiles (`off`, `10%`, `100%`).
Measure: P99 latency increase, CPU overhead, memory overhead, network
egress for trace export.

---

## 5. What goes in the dissertation

| Chapter | Platform artefact that anchors it |
|---------|-----------------------------------|
| Background & related work | TraceFlix prior work + this platform's contribution |
| Methodology | This document + experimental protocol |
| System design | Architecture diagrams, hexagonal DDD as a reproducibility argument |
| Detection framework | Fusion model, trained on event streams from this platform |
| Evaluation | Phase 2–4 results across all detectors |
| Threats to validity | Why one platform is generalisable; how domain events transfer |

---

## 6. Two outputs from one platform

This testbed produces two publishable papers:

**Paper 1 — Engineering / methodology**
*"Observability-aware DDD: a pattern for research-grade microservice
testbeds"*. Argues that hexagonal architecture + domain-events-as-signals
gives researchers a reproducible, semantically-rich testbed. The pattern
itself is the contribution.

**Paper 2 — Detection**
*"Multi-signal anomaly detection with semantic event labels: evidence
from a regulated domain"*. Uses the testbed to evaluate the proposed
detector, with the AML domain providing realistic ground truth.

The first paper is unusual — most researchers treat their testbed as
incidental — and that unusual angle is its strength.

---

## 7. Why this differs from the TraceFlix dissertation work

TraceFlix was an empirical study of *existing* detectors on a Kubernetes
testbed. The platform was **instrumental** — useful, but not the main
contribution.

This work elevates the platform to a **research artefact in its own
right**: the AML platform IS the contribution as much as the detector
is, because the observability-as-domain-design pattern is novel and
reusable.

---

*Last updated: see `git log -- docs/research-design.md`.*

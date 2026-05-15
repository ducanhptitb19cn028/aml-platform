"""
LLM Engine — Local Qwen2.5-3B inference via Ollama + LoRA fine-tuning.

Inference:  Ollama REST API at http://ollama:11434  (qwen2.5:3b, quantized)
Training:   LoRA fine-tuning via PEFT on accumulated labeled incident data
            (runs in a background thread; non-blocking)

Endpoints:
  POST /v1/analyze        — analyze an incident (Ollama inference)
  POST /v1/train          — add a labeled training example
  POST /v1/train/start    — trigger LoRA fine-tuning background job
  GET  /v1/train/status   — check fine-tuning status + loss history
  GET  /v1/stats          — overall statistics
  GET  /v1/analyses       — recent LLM analyses (last 50)
  GET  /v1/health         — liveness probe
  GET  /actuator/health   — Spring-style alias

Background:
  Kafka consumer on aiops.incidents   → Ollama analysis → aml.llm.analysis
  Kafka consumer on aiops.outcomes    → auto-accumulates training examples
"""

from __future__ import annotations

import json
import logging
import os
import threading
import uuid
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from threading import Event
from typing import Any

import httpx
from fastapi import FastAPI, BackgroundTasks, HTTPException
from prometheus_fastapi_instrumentator import Instrumentator
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from trainer import TrainingState, TrainingStatus, run_lora_training

# ── Configuration ──────────────────────────────────────────────────────────────

KAFKA_BOOTSTRAP     = os.getenv("KAFKA_BOOTSTRAP",   "localhost:9092")
OLLAMA_URL          = os.getenv("OLLAMA_URL",         "http://ollama:11434")
OLLAMA_MODEL        = os.getenv("OLLAMA_MODEL",       "qwen2.5:3b")
TOPIC_INCIDENTS     = "aiops.incidents"
TOPIC_OUTCOMES      = "aiops.outcomes"
TOPIC_LLM_ANALYSIS  = "aml.llm.analysis"
TOPIC_LOGS          = "aiops.telemetry.logs"
TOPIC_TRACES        = "aiops.telemetry.traces"
TOPIC_FEATURES      = "aiops.features"

logger = logging.getLogger("llm-engine")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
)

SYSTEM_PROMPT = (
    "You are an expert AML (Anti-Money Laundering) and AIOps analyst. "
    "You analyze incidents from a financial-crime detection platform and provide:\n"
    "1. A concise explanation of what is happening\n"
    "2. The likely root cause\n"
    "3. The recommended remediation action\n"
    "4. AML compliance risk level (LOW / MEDIUM / HIGH / CRITICAL)\n\n"
    "Respond ONLY with a JSON object with keys: "
    "explanation, rootCause, recommendation, amlRisk, confidence (float 0-1)."
)

# ── Pydantic schemas ───────────────────────────────────────────────────────────

class IncidentInput(BaseModel):
    incidentId:       str
    service:          str
    anomalyScore:     float
    rootCause:        str | None = None
    breachEtaMinutes: int        = 999
    confidence:       float      = 0.5


class TrainingExample(BaseModel):
    incidentId:   str
    service:      str
    anomalyScore: float
    rootCause:    str | None
    outcome:      str            # RESOLVED | NO_EFFECT | DEGRADED_FURTHER
    actionTaken:  str | None = None
    explanation:  str | None = None


class LlmAnalysis(BaseModel):
    analysisId:     str
    incidentId:     str
    analyzedAt:     str
    model:          str
    explanation:    str
    rootCause:      str
    recommendation: str
    amlRisk:        str
    confidence:     float
    cacheHit:       bool = False   # True when fine-tuned adapter was used
    trainingSize:   int  = 0


class HealthResponse(BaseModel):
    status:      str = "UP"
    ollamaReady: bool = False
    model:       str = OLLAMA_MODEL


class StatsResponse(BaseModel):
    trainingExamples: int
    totalAnalyses:    int
    modelId:          str
    adapterReady:     bool
    ollamaUrl:        str


class TrainingStatusResponse(BaseModel):
    status:          str
    startedAt:       str | None
    completedAt:     str | None
    trainingSamples: int
    trainLoss:       float | None
    error:           str | None
    adapterReady:    bool
    lossHistory:     list[float]
    minExamples:     int


# ── Shared state ───────────────────────────────────────────────────────────────

_training_examples: list[dict]    = []
_recent_analyses:   list[LlmAnalysis] = []
_total_analyses:    int           = 0
_train_state:       TrainingState = TrainingState()
_state_lock         = threading.Lock()
_stop_event         = Event()        # signals Kafka loops to exit
_train_stop         = Event()        # signals training to abort

# MELT rolling buffers — written by background consumers, read by incident consumer
_log_buffer:    dict = defaultdict(lambda: deque(maxlen=10))  # service -> last 10 LogSignals
_trace_buffer:  dict = defaultdict(lambda: deque(maxlen=5))   # service -> last 5 TraceSignals
_metric_buffer: dict = defaultdict(dict)                      # service -> {metricName -> FeatureRecord}


# ── Ollama inference ───────────────────────────────────────────────────────────

def _ollama_chat(prompt_text: str, timeout: float = 120.0) -> dict[str, Any]:
    """Call Ollama chat API. Returns parsed JSON dict from the model response."""
    payload = {
        "model":  OLLAMA_MODEL,
        "stream": False,
        "format": "json",           # force JSON output
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user",   "content": prompt_text},
        ],
        "options": {
            "temperature": 0.1,     # low temp for deterministic JSON
            "num_predict": 400,
        },
    }
    resp = httpx.post(f"{OLLAMA_URL}/api/chat", json=payload, timeout=timeout)
    resp.raise_for_status()
    content = resp.json()["message"]["content"]
    # Strip markdown fences if present
    raw = content.strip()
    if raw.startswith("```"):
        parts = raw.split("```")
        raw = parts[1].lstrip("json").strip() if len(parts) > 1 else raw
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {
            "explanation":    raw[:300],
            "rootCause":      "unknown",
            "recommendation": "Manual investigation required",
            "amlRisk":        "MEDIUM",
            "confidence":     0.5,
        }


def _analyze(incident: IncidentInput) -> LlmAnalysis:
    global _total_analyses

    with _state_lock:
        logs    = list(_log_buffer.get(incident.service, []))
        traces  = list(_trace_buffer.get(incident.service, []))
        metrics = dict(_metric_buffer.get(incident.service, {}))

    parts = [
        "Analyze this AIOps incident:",
        f"  Service: {incident.service}",
        f"  Anomaly score: {incident.anomalyScore:.4f}",
        f"  Root cause signal: {incident.rootCause or 'unknown'}",
        f"  Breach ETA: {incident.breachEtaMinutes} min",
        f"  ML confidence: {incident.confidence:.4f}",
    ]

    if metrics:
        parts.append("\nRecent metrics (last 60s window):")
        for name, fr in sorted(metrics.items()):
            parts.append(
                f"  {name}: avg={fr.get('avgValue', 0):.3f}"
                f" max={fr.get('maxValue', 0):.3f}"
                f" rateOfChange={fr.get('rateOfChange', 0):+.3f}"
            )

    if logs:
        parts.append("\nRecent ERROR logs:")
        for entry in logs[-5:]:
            ts  = str(entry.get("timestamp", ""))[:19]
            msg = str(entry.get("message", ""))[:200]
            parts.append(f"  [{ts}] {msg}")

    if traces:
        parts.append("\nRecent traces:")
        for t in traces[-5:]:
            ts  = str(t.get("timestamp", ""))[:19]
            op  = str(t.get("operation", "?"))[:80]
            dur = t.get("durationMs", 0)
            sc  = t.get("statusCode", 0)
            parts.append(f"  [{ts}] {op} | {dur}ms | status={sc}")

    parts.append("\nReturn JSON only.")
    prompt = "\n".join(parts)

    result = _ollama_chat(prompt)

    with _state_lock:
        _total_analyses += 1
        adapter_ready   = _train_state.adapterReady
        training_size   = len(_training_examples)

    return LlmAnalysis(
        analysisId     = str(uuid.uuid4()),
        incidentId     = incident.incidentId,
        analyzedAt     = datetime.now(timezone.utc).isoformat(),
        model          = OLLAMA_MODEL,
        explanation    = result.get("explanation", ""),
        rootCause      = result.get("rootCause", incident.rootCause or "unknown"),
        recommendation = result.get("recommendation", ""),
        amlRisk        = result.get("amlRisk", "MEDIUM"),
        confidence     = float(result.get("confidence", 0.5)),
        cacheHit       = adapter_ready,   # repurposed: True = fine-tuned adapter in use
        trainingSize   = training_size,
    )


def _is_ollama_ready() -> bool:
    try:
        resp = httpx.get(f"{OLLAMA_URL}/api/tags", timeout=5.0)
        return resp.status_code == 200
    except Exception:
        return False


# ── Kafka helpers ──────────────────────────────────────────────────────────────

def _make_consumer(group_id: str):
    try:
        from confluent_kafka import Consumer
        return Consumer({
            "bootstrap.servers":  KAFKA_BOOTSTRAP,
            "group.id":           group_id,
            "auto.offset.reset":  "latest",
            "enable.auto.commit": True,
        })
    except ImportError:
        logger.warning("confluent_kafka not available")
        return None


def _make_producer():
    try:
        from confluent_kafka import Producer
        return Producer({"bootstrap.servers": KAFKA_BOOTSTRAP})
    except ImportError:
        logger.warning("confluent_kafka not available")
        return None


def _store_analysis(a: LlmAnalysis) -> None:
    with _state_lock:
        _recent_analyses.append(a)
        if len(_recent_analyses) > 50:
            _recent_analyses.pop(0)


def _incident_consumer_loop(producer) -> None:
    consumer = _make_consumer("aiops-llm-engine-incidents")
    if consumer is None:
        return
    consumer.subscribe([TOPIC_INCIDENTS])
    logger.info("Incident consumer subscribed to %s", TOPIC_INCIDENTS)

    while not _stop_event.is_set():
        msg = consumer.poll(timeout=1.0)
        if msg is None:
            continue
        if msg.error():
            logger.error("Kafka error: %s", msg.error())
            continue
        try:
            p       = json.loads(msg.value().decode())
            rca     = (p.get("rootCauseRanking") or [{}])
            inc     = IncidentInput(
                incidentId       = p.get("incidentId", str(uuid.uuid4())),
                service          = (p.get("affectedServices") or ["unknown"])[0],
                anomalyScore     = p.get("anomalyScore", 0.5),
                rootCause        = rca[0].get("component") if rca else None,
                breachEtaMinutes = p.get("breachEtaMinutes", 999),
                confidence       = p.get("confidence", 0.5),
            )
            analysis = _analyze(inc)
            _store_analysis(analysis)
            if producer:
                producer.produce(
                    TOPIC_LLM_ANALYSIS,
                    key   = inc.service.encode(),
                    value = analysis.model_dump_json().encode(),
                )
                producer.poll(0)
            logger.info("LLM analyzed incidentId=%s amlRisk=%s",
                        inc.incidentId, analysis.amlRisk)
        except Exception as exc:
            logger.error("Incident analysis error: %s", exc, exc_info=True)

    consumer.close()
    if producer:
        producer.flush()


def _outcome_consumer_loop() -> None:
    """Auto-collect labeled outcomes as training examples."""
    consumer = _make_consumer("aiops-llm-engine-outcomes")
    if consumer is None:
        return
    consumer.subscribe([TOPIC_OUTCOMES])
    logger.info("Outcome consumer subscribed to %s", TOPIC_OUTCOMES)

    while not _stop_event.is_set():
        msg = consumer.poll(timeout=1.0)
        if msg is None:
            continue
        if msg.error():
            continue
        try:
            p = json.loads(msg.value().decode())
            ex = {
                "incidentId":  p.get("incidentId", str(uuid.uuid4())),
                "service":     p.get("incidentId", "unknown"),   # best guess
                "anomalyScore": float(p.get("sloBefore", 0.5)),
                "rootCause":   None,
                "outcome":     p.get("label", "NO_EFFECT"),
                "actionTaken": None,
                "explanation": None,
            }
            with _state_lock:
                _training_examples.append(ex)
            logger.info("Auto-collected training example from outcome label=%s total=%d",
                        ex["outcome"], len(_training_examples))
        except Exception as exc:
            logger.warning("Outcome consumer error: %s", exc)

    consumer.close()


def _log_consumer_loop() -> None:
    consumer = _make_consumer("aiops-llm-engine-logs")
    if consumer is None:
        return
    consumer.subscribe([TOPIC_LOGS])
    logger.info("Log consumer subscribed to %s", TOPIC_LOGS)

    while not _stop_event.is_set():
        msg = consumer.poll(timeout=1.0)
        if msg is None or msg.error():
            continue
        try:
            p = json.loads(msg.value().decode())
            service = p.get("service", "unknown")
            with _state_lock:
                _log_buffer[service].append(p)
        except Exception as exc:
            logger.debug("Log buffer error: %s", exc)

    consumer.close()


def _trace_consumer_loop() -> None:
    consumer = _make_consumer("aiops-llm-engine-traces")
    if consumer is None:
        return
    consumer.subscribe([TOPIC_TRACES])
    logger.info("Trace consumer subscribed to %s", TOPIC_TRACES)

    while not _stop_event.is_set():
        msg = consumer.poll(timeout=1.0)
        if msg is None or msg.error():
            continue
        try:
            p = json.loads(msg.value().decode())
            service = p.get("service", "unknown")
            with _state_lock:
                _trace_buffer[service].append(p)
        except Exception as exc:
            logger.debug("Trace buffer error: %s", exc)

    consumer.close()


def _feature_consumer_loop() -> None:
    consumer = _make_consumer("aiops-llm-engine-features")
    if consumer is None:
        return
    consumer.subscribe([TOPIC_FEATURES])
    logger.info("Feature consumer subscribed to %s", TOPIC_FEATURES)

    while not _stop_event.is_set():
        msg = consumer.poll(timeout=1.0)
        if msg is None or msg.error():
            continue
        try:
            p       = json.loads(msg.value().decode())
            service = p.get("service", "unknown")
            metric  = p.get("metricName", "unknown")
            with _state_lock:
                _metric_buffer[service][metric] = p
        except Exception as exc:
            logger.debug("Feature buffer error: %s", exc)

    consumer.close()


# ── FastAPI lifespan ───────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting LLM Engine (Ollama + Qwen2.5)…")
    if _is_ollama_ready():
        logger.info("Ollama is reachable at %s", OLLAMA_URL)
    else:
        logger.warning("Ollama not reachable at %s — inference will fail until it starts", OLLAMA_URL)

    producer = _make_producer()
    threads: list[threading.Thread] = [
        threading.Thread(target=_incident_consumer_loop, args=(producer,), daemon=True, name="kafka-incidents"),
        threading.Thread(target=_outcome_consumer_loop,  args=(),           daemon=True, name="kafka-outcomes"),
        threading.Thread(target=_log_consumer_loop,      args=(),           daemon=True, name="kafka-logs"),
        threading.Thread(target=_trace_consumer_loop,    args=(),           daemon=True, name="kafka-traces"),
        threading.Thread(target=_feature_consumer_loop,  args=(),           daemon=True, name="kafka-features"),
    ]
    for t in threads:
        t.start()
    app.state.producer = producer
    logger.info("LLM Engine ready")

    yield

    logger.info("Shutting down…")
    _stop_event.set()
    for t in threads:
        t.join(timeout=10)
    if producer:
        producer.flush(timeout=5)


# ── FastAPI app ────────────────────────────────────────────────────────────────

app = FastAPI(title="AIOps LLM Engine — Qwen2.5", version="0.2.0", lifespan=lifespan)

Instrumentator().instrument(app).expose(app)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/v1/health", response_model=HealthResponse)
@app.get("/actuator/health", response_model=HealthResponse)
async def health():
    ready = _is_ollama_ready()
    return HealthResponse(status="UP", ollamaReady=ready, model=OLLAMA_MODEL)


@app.get("/v1/stats", response_model=StatsResponse)
async def stats():
    with _state_lock:
        return StatsResponse(
            trainingExamples = len(_training_examples),
            totalAnalyses    = _total_analyses,
            modelId          = OLLAMA_MODEL,
            adapterReady     = _train_state.adapterReady,
            ollamaUrl        = OLLAMA_URL,
        )


@app.post("/v1/analyze", response_model=LlmAnalysis)
async def analyze(incident: IncidentInput):
    if not _is_ollama_ready():
        raise HTTPException(status_code=503, detail=f"Ollama not ready at {OLLAMA_URL}")
    try:
        analysis = _analyze(incident)
        _store_analysis(analysis)
        return analysis
    except Exception as exc:
        logger.error("Analyze error: %s", exc, exc_info=True)
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/v1/train")
async def add_training_example(example: TrainingExample):
    ex = example.model_dump()
    with _state_lock:
        _training_examples.append(ex)
        count = len(_training_examples)
    logger.info("Training example added: service=%s outcome=%s total=%d",
                example.service, example.outcome, count)
    return {"status": "accepted", "trainingExamples": count}


@app.post("/v1/train/start")
async def start_training(background_tasks: BackgroundTasks):
    with _state_lock:
        if _train_state.status == TrainingStatus.RUNNING:
            return {"status": "already_running", "message": "Training is already in progress"}
        examples = list(_training_examples)

    if len(examples) < 5:
        raise HTTPException(
            status_code=400,
            detail=f"Need at least 5 training examples (have {len(examples)}). "
                   f"Add more via POST /v1/train or wait for Kafka outcomes."
        )

    _train_stop.clear()

    def _run():
        run_lora_training(examples, _train_state, _train_stop)

    t = threading.Thread(target=_run, daemon=True, name="lora-trainer")
    t.start()

    return {
        "status":   "started",
        "examples": len(examples),
        "model":    os.getenv("TRAINING_MODEL", "Qwen/Qwen2.5-0.5B-Instruct"),
        "message":  "LoRA fine-tuning started. Check GET /v1/train/status for progress.",
    }


@app.post("/v1/train/stop")
async def stop_training():
    _train_stop.set()
    return {"status": "stop_requested"}


@app.get("/v1/train/status", response_model=TrainingStatusResponse)
async def training_status():
    from trainer import MIN_EXAMPLES
    return TrainingStatusResponse(
        status          = _train_state.status.value,
        startedAt       = _train_state.startedAt,
        completedAt     = _train_state.completedAt,
        trainingSamples = _train_state.trainingSamples,
        trainLoss       = _train_state.trainLoss,
        error           = _train_state.error,
        adapterReady    = _train_state.adapterReady,
        lossHistory     = _train_state.lossHistory,
        minExamples     = MIN_EXAMPLES,
    )


@app.get("/v1/analyses", response_model=list[LlmAnalysis])
async def recent_analyses(limit: int = 20):
    with _state_lock:
        return list(reversed(_recent_analyses))[:limit]

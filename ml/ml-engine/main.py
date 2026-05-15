"""
ML Engine — FastAPI service for AIOps anomaly detection, RCA, and breach prediction.

Endpoints:
  POST /v1/incidents/score   — score a list of FeatureRecords → Incident
  POST /v1/models/update     — accept IncidentOutcome for online retraining
  GET  /v1/health            — liveness probe
  GET  /actuator/health      — alias for Spring-style compatibility

Background:
  Confluent Kafka consumer on aiops.features → scores → publishes to aiops.incidents
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import threading
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI, HTTPException
from prometheus_fastapi_instrumentator import Instrumentator
from pydantic import BaseModel, Field

from models import AnomalyDetector, BreachPredictor, RootCauseLocaliser

# ── Configuration ─────────────────────────────────────────────────────────────

KAFKA_BOOTSTRAP  = os.getenv("KAFKA_BOOTSTRAP", "localhost:9092")
TOPIC_FEATURES   = "aiops.features"
TOPIC_INCIDENTS  = "aiops.incidents"
SLO_THRESHOLD_MS = float(os.getenv("SLO_THRESHOLD_MS", "500.0"))

logger = logging.getLogger("ml-engine")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
)

# ── Shared ML models (module-level singletons) ────────────────────────────────

anomaly_detector  = AnomalyDetector()
root_cause_loc    = RootCauseLocaliser()
breach_predictor  = BreachPredictor()


# ── Pydantic schemas ──────────────────────────────────────────────────────────

class FeatureRecord(BaseModel):
    windowId:      str
    service:       str
    windowStart:   str
    windowEnd:     str
    avgValue:      float
    maxValue:      float
    rateOfChange:  float
    metricName:    str
    sampleCount:   int


class RootCauseEntry(BaseModel):
    component: str
    weight:    float


class Incident(BaseModel):
    incidentId:        str
    detectedAt:        str
    anomalyScore:      float
    affectedServices:  list[str]
    rootCauseRanking:  list[RootCauseEntry]
    breachEtaMinutes:  int
    confidence:        float


class IncidentOutcome(BaseModel):
    outcomeId:    str
    incidentId:   str
    decisionId:   str
    label:        str   # RESOLVED | NO_EFFECT | DEGRADED_FURTHER
    sloBefore:    float
    sloAfter:     float
    evaluatedAt:  str


class HealthResponse(BaseModel):
    status: str = "UP"


class ScoreRequest(BaseModel):
    features: list[FeatureRecord]


# ── Kafka helpers ─────────────────────────────────────────────────────────────

def _make_consumer():
    """Create and return a Confluent Kafka Consumer."""
    try:
        from confluent_kafka import Consumer
        return Consumer({
            "bootstrap.servers":  KAFKA_BOOTSTRAP,
            "group.id":           "aiops-ml-engine",
            "auto.offset.reset":  "latest",
            "enable.auto.commit": True,
        })
    except ImportError:
        logger.warning("confluent_kafka not available — Kafka consumer disabled")
        return None


def _make_producer():
    """Create and return a Confluent Kafka Producer."""
    try:
        from confluent_kafka import Producer
        return Producer({"bootstrap.servers": KAFKA_BOOTSTRAP})
    except ImportError:
        logger.warning("confluent_kafka not available — Kafka producer disabled")
        return None


def _score_feature_record(fr: FeatureRecord) -> Incident:
    """Score a single FeatureRecord and return an Incident."""
    features = {
        "avg_value":      fr.avgValue,
        "max_value":      fr.maxValue,
        "rate_of_change": fr.rateOfChange,
        "sample_count":   float(fr.sampleCount),
    }

    # Feed into root cause localiser history
    root_cause_loc.observe(fr.service, "avg_value",      fr.avgValue)
    root_cause_loc.observe(fr.service, "max_value",      fr.maxValue)
    root_cause_loc.observe(fr.service, "rate_of_change", fr.rateOfChange)

    # Feed p95 proxy into breach predictor (using avgValue as proxy when no real p95)
    p95_proxy = fr.maxValue   # max over the window is a reasonable p95 proxy
    breach_predictor.record(fr.service, p95_proxy)

    anomaly_score = anomaly_detector.score(features)

    rca_entries = root_cause_loc.localise(fr.service, {
        **features, "anomaly_score": anomaly_score
    })
    rca_list = [RootCauseEntry(component=e.component, weight=e.weight) for e in rca_entries]

    eta = breach_predictor.predict_eta(fr.service, p95_proxy, SLO_THRESHOLD_MS)

    # Confidence derived from how far the score is from 0.5
    confidence = min(1.0, abs(anomaly_score - 0.5) * 2 + 0.5)
    confidence = round(confidence, 4)

    return Incident(
        incidentId=str(uuid.uuid4()),
        detectedAt=datetime.now(timezone.utc).isoformat(),
        anomalyScore=round(anomaly_score, 4),
        affectedServices=[fr.service],
        rootCauseRanking=rca_list,
        breachEtaMinutes=eta,
        confidence=confidence,
    )


# ── Kafka consumer thread ─────────────────────────────────────────────────────

_stop_event = threading.Event()


def _kafka_consumer_loop(producer) -> None:
    consumer = _make_consumer()
    if consumer is None:
        logger.warning("Kafka consumer unavailable — exiting consumer loop")
        return

    consumer.subscribe([TOPIC_FEATURES])
    logger.info("Kafka consumer subscribed to %s", TOPIC_FEATURES)

    while not _stop_event.is_set():
        msg = consumer.poll(timeout=1.0)
        if msg is None:
            continue
        if msg.error():
            logger.error("Kafka consumer error: %s", msg.error())
            continue
        try:
            payload = json.loads(msg.value().decode("utf-8"))
            fr      = FeatureRecord(**payload)
            incident = _score_feature_record(fr)

            if producer is not None:
                incident_json = incident.model_dump_json().encode("utf-8")
                producer.produce(
                    TOPIC_INCIDENTS,
                    key=fr.service.encode("utf-8"),
                    value=incident_json,
                    callback=_delivery_report,
                )
                producer.poll(0)
            logger.info(
                "Scored service=%s anomalyScore=%.4f breachEta=%d confidence=%.4f",
                fr.service, incident.anomalyScore, incident.breachEtaMinutes, incident.confidence,
            )
        except Exception as exc:
            logger.error("Error processing feature record: %s", exc, exc_info=True)

    consumer.close()
    if producer:
        producer.flush()
    logger.info("Kafka consumer loop exited cleanly")


def _delivery_report(err, msg) -> None:
    if err:
        logger.error("Kafka delivery failed topic=%s: %s", msg.topic(), err)
    else:
        logger.debug("Delivered to %s [%d]", msg.topic(), msg.partition())


# ── FastAPI lifespan ──────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    logger.info("Starting ML Engine…")
    anomaly_detector.load_or_bootstrap()

    producer = _make_producer()
    consumer_thread = threading.Thread(
        target=_kafka_consumer_loop,
        args=(producer,),
        daemon=True,
        name="kafka-consumer",
    )
    consumer_thread.start()
    app.state.consumer_thread = consumer_thread
    app.state.producer        = producer
    logger.info("ML Engine ready")

    yield

    # Shutdown
    logger.info("Shutting down ML Engine…")
    _stop_event.set()
    consumer_thread.join(timeout=10)
    if producer:
        producer.flush(timeout=5)


# ── FastAPI app ───────────────────────────────────────────────────────────────

app = FastAPI(
    title="AIOps ML Engine",
    version="0.1.0",
    lifespan=lifespan,
)

Instrumentator().instrument(app).expose(app)


@app.get("/v1/health", response_model=HealthResponse)
@app.get("/actuator/health", response_model=HealthResponse)
async def health():
    return HealthResponse(status="UP")


@app.post("/v1/incidents/score", response_model=list[Incident])
async def score_incidents(request: ScoreRequest):
    """
    Score a batch of FeatureRecords.

    Returns one Incident per FeatureRecord.
    """
    if not request.features:
        raise HTTPException(status_code=400, detail="features list must not be empty")

    results: list[Incident] = []
    for fr in request.features:
        try:
            incident = _score_feature_record(fr)
            results.append(incident)
        except Exception as exc:
            logger.error("Score error for service=%s: %s", fr.service, exc, exc_info=True)
            raise HTTPException(status_code=500, detail=f"Scoring error: {exc}") from exc
    return results


@app.post("/v1/models/update")
async def update_model(outcome: IncidentOutcome):
    """
    Accept a labelled IncidentOutcome to retrain / update the anomaly detector.
    Also feeds into the breach predictor history via SLO delta.
    """
    features: dict[str, Any] = {
        "avg_value":      outcome.sloAfter,
        "max_value":      outcome.sloAfter,
        "rate_of_change": outcome.sloAfter - outcome.sloBefore,
        "sample_count":   1.0,
    }
    anomaly_detector.update(features, outcome.label)

    # Optionally record sloAfter as a p95 proxy for breach prediction
    breach_predictor.record(outcome.incidentId, outcome.sloAfter * 1000)  # convert s → ms

    logger.info("Model updated with outcome label=%s incidentId=%s",
                outcome.label, outcome.incidentId)
    return {"status": "accepted", "incidentId": outcome.incidentId}

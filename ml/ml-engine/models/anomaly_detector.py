"""
AnomalyDetector — IsolationForest-based anomaly scoring.

The detector maintains a buffer of labelled samples for online retraining.
Scores are normalized to [0, 1] using the raw decision function output:
  score = 0.5 - 0.5 * decision_function(x)
which maps the IF output range [-0.5, 0.5] → [0, 1].
"""

from __future__ import annotations

import logging
import threading
from typing import Any

import joblib
import numpy as np
from sklearn.ensemble import IsolationForest

logger = logging.getLogger(__name__)

# Feature order must match across all calls.
FEATURE_KEYS = [
    "avg_value",
    "max_value",
    "rate_of_change",
    "sample_count",
]

BOOTSTRAP_SAMPLES = 500
REFIT_THRESHOLD   = 50  # new labelled samples before refit


def _extract_vector(features: dict[str, Any]) -> np.ndarray:
    """Extract a fixed-length feature vector from a feature dict."""
    return np.array(
        [float(features.get(k, 0.0)) for k in FEATURE_KEYS],
        dtype=np.float64,
    ).reshape(1, -1)


def _bootstrap_data() -> np.ndarray:
    """Generate synthetic normal-looking data to seed the initial model."""
    rng = np.random.default_rng(42)
    return rng.normal(loc=[1.0, 1.5, 0.0, 10.0], scale=[0.2, 0.3, 0.1, 3.0],
                      size=(BOOTSTRAP_SAMPLES, len(FEATURE_KEYS)))


class AnomalyDetector:
    """
    Thread-safe IsolationForest wrapper.

    score() → float in [0, 1] where higher = more anomalous.
    update() accumulates labelled samples and refits when threshold is reached.
    """

    MODEL_PATH = "/tmp/anomaly_detector.joblib"

    def __init__(self) -> None:
        self._lock              = threading.Lock()
        self._model: IsolationForest | None = None
        self._pending_X: list[np.ndarray] = []
        self._pending_y: list[str]        = []
        self._new_since_fit: int = 0

    # ── public API ────────────────────────────────────────────────────────────

    def load_or_bootstrap(self) -> None:
        """Load a persisted model or bootstrap with synthetic data."""
        with self._lock:
            try:
                self._model = joblib.load(self.MODEL_PATH)
                logger.info("Loaded IsolationForest model from %s", self.MODEL_PATH)
            except (FileNotFoundError, Exception):
                logger.info("No saved model found — bootstrapping IsolationForest on synthetic data")
                X = _bootstrap_data()
                self._model = IsolationForest(contamination=0.05, random_state=42)
                self._model.fit(X)
                self._save()

    def score(self, features: dict[str, Any]) -> float:
        """
        Return an anomaly score in [0, 1].
        Returns 0.5 (neutral) if the model is not yet ready.
        """
        with self._lock:
            if self._model is None:
                return 0.5
            x = _extract_vector(features)
            # decision_function returns positive for inliers, negative for outliers
            raw = float(self._model.decision_function(x)[0])
            # Normalise: map [-0.5, 0.5] → [0, 1], clamp to [0, 1]
            normalised = max(0.0, min(1.0, 0.5 - raw))
            return normalised

    def update(self, features: dict[str, Any], label: str) -> None:
        """
        Accumulate a labelled sample.  Refits when REFIT_THRESHOLD new samples accumulate.
        label should be one of: 'RESOLVED', 'NO_EFFECT', 'DEGRADED_FURTHER'.
        """
        x = _extract_vector(features)
        with self._lock:
            self._pending_X.append(x.flatten())
            self._pending_y.append(label)
            self._new_since_fit += 1
            if self._new_since_fit >= REFIT_THRESHOLD:
                self._refit()

    # ── private ───────────────────────────────────────────────────────────────

    def _refit(self) -> None:
        """Refit IsolationForest on all accumulated data (called under lock)."""
        try:
            X_all = np.vstack([_bootstrap_data()] + [np.array(x) for x in self._pending_X])
            new_model = IsolationForest(contamination=0.05, random_state=42)
            new_model.fit(X_all)
            self._model      = new_model
            self._new_since_fit = 0
            self._save()
            logger.info("IsolationForest refitted on %d samples", len(X_all))
        except Exception as exc:
            logger.error("Refit failed: %s", exc)

    def _save(self) -> None:
        try:
            joblib.dump(self._model, self.MODEL_PATH)
        except Exception as exc:
            logger.warning("Could not persist model: %s", exc)

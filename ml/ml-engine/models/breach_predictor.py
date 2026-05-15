"""
BreachPredictor — linear-regression-based SLO breach ETA estimator.

Maintains a sliding window of p95 latency observations per service.
Fits a linear trend through the last 10 points and extrapolates to the
SLO threshold, returning the estimated minutes until breach.
"""

from __future__ import annotations

import collections
import logging
import threading
from dataclasses import dataclass, field

import numpy as np
from sklearn.linear_model import LinearRegression

logger = logging.getLogger(__name__)

WINDOW_SIZE     = 10   # number of p95 observations per service
INTERVAL_S      = 30   # seconds between observations (matches Prometheus scrape)
NO_BREACH_ETA   = 999  # sentinel: breach not predicted


class BreachPredictor:
    """
    Thread-safe p95 latency trend predictor.

    Usage:
        predictor.record(service, p95_value)  # feed each scrape
        eta = predictor.predict_eta(service, current_p95, slo_threshold_ms)
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        # { service: deque[float] }  values in milliseconds
        self._windows: dict[str, collections.deque[float]] = (
            collections.defaultdict(lambda: collections.deque(maxlen=WINDOW_SIZE))
        )

    def record(self, service: str, p95_ms: float) -> None:
        """Append a new p95 latency observation for a service."""
        with self._lock:
            self._windows[service].append(p95_ms)

    def predict_eta(
        self,
        service: str,
        current_p95: float,
        slo_threshold_ms: float,
    ) -> int:
        """
        Estimate minutes until p95 latency breaches slo_threshold_ms.

        Returns:
            Estimated minutes to breach (int), or NO_BREACH_ETA (999) if:
            - Not enough data
            - The trend is flat or improving
            - Breach is not projected within a reasonable horizon
        """
        with self._lock:
            window = list(self._windows[service])

        # Ensure the current observation is included
        if not window or window[-1] != current_p95:
            window.append(current_p95)
        if len(window) < 3:
            logger.debug("Not enough data for %s (%d points)", service, len(window))
            return NO_BREACH_ETA

        # X = time steps in minutes, Y = p95 latency
        n   = len(window)
        X   = np.array([(i * INTERVAL_S) / 60.0 for i in range(n)]).reshape(-1, 1)
        y   = np.array(window, dtype=np.float64)

        try:
            model = LinearRegression()
            model.fit(X, y)
            slope     = float(model.coef_[0])
            intercept = float(model.intercept_)
        except Exception as exc:
            logger.warning("LinearRegression fit failed for %s: %s", service, exc)
            return NO_BREACH_ETA

        if slope <= 0:
            # Trend is flat or improving — no breach predicted
            return NO_BREACH_ETA

        # Solve: intercept + slope * t = slo_threshold_ms
        # t = (slo_threshold_ms - intercept) / slope
        current_time_min = X[-1, 0]
        breach_time_min  = (slo_threshold_ms - intercept) / slope

        eta_min = breach_time_min - current_time_min
        if eta_min <= 0:
            # Already breached
            return 0
        if eta_min > 1440:
            # More than a day away — treat as no imminent breach
            return NO_BREACH_ETA

        logger.debug(
            "BreachPredictor service=%s slope=%.4f eta_min=%.1f threshold=%.1f",
            service, slope, eta_min, slo_threshold_ms,
        )
        return max(0, round(eta_min))

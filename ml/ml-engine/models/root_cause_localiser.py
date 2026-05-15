"""
RootCauseLocaliser — correlation-based root cause ranking.

Maintains a sliding window of (service, metric_name, value) observations.
When asked to localise, it correlates each service+metric trajectory against
the anomalous service's p95 latency trajectory and ranks by absolute
Pearson correlation.
"""

from __future__ import annotations

import collections
import logging
import math
import threading
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)

WINDOW_SIZE = 100  # observations per (service, metric) pair


@dataclass
class RootCauseEntry:
    component: str
    weight: float


class RootCauseLocaliser:
    """
    Thread-safe sliding-window correlator.

    Usage:
        localiser.observe(service, metric_name, value)   # feed new data
        entries = localiser.localise(anomalous_service, features)
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        # { (service, metric_name): deque[float] }
        self._windows: dict[tuple[str, str], collections.deque[float]] = (
            collections.defaultdict(lambda: collections.deque(maxlen=WINDOW_SIZE))
        )

    def observe(self, service: str, metric_name: str, value: float) -> None:
        """Record a new observation for (service, metric_name)."""
        with self._lock:
            self._windows[(service, metric_name)].append(value)

    def localise(self, service: str, features: dict[str, Any]) -> list[RootCauseEntry]:
        """
        Rank all known (service, metric) pairs by Pearson correlation against
        the anomalous service's avg_value trajectory.

        Returns a sorted list with the highest-correlation entries first.
        """
        with self._lock:
            # Build the reference trajectory for the anomalous service
            ref_key = (service, "avg_value")
            ref_series = list(self._windows.get(ref_key, []))

            if len(ref_series) < 3:
                # Not enough data — fall back to feature-based heuristic
                return self._heuristic_fallback(service, features)

            results: list[RootCauseEntry] = []

            for (svc, metric), window in self._windows.items():
                if svc == service and metric == "avg_value":
                    continue  # skip the reference series itself
                series = list(window)
                # Align lengths
                min_len = min(len(ref_series), len(series))
                if min_len < 3:
                    continue
                r = pearson_correlation(ref_series[-min_len:], series[-min_len:])
                if math.isnan(r):
                    continue
                results.append(RootCauseEntry(
                    component=f"{svc}.{metric}",
                    weight=round(abs(r), 4),
                ))

            if not results:
                return self._heuristic_fallback(service, features)

            results.sort(key=lambda e: e.weight, reverse=True)
            return results[:10]  # top 10

    # ── private helpers ───────────────────────────────────────────────────────

    @staticmethod
    def _heuristic_fallback(service: str, features: dict[str, Any]) -> list[RootCauseEntry]:
        """
        When not enough history is available, construct a single entry
        pointing at the service itself with a weight derived from anomaly score.
        """
        score = float(features.get("anomaly_score", 0.5))
        return [RootCauseEntry(component=service, weight=round(score, 4))]


def pearson_correlation(x: list[float], y: list[float]) -> float:
    """Compute Pearson r between two equal-length lists."""
    n = len(x)
    if n < 2:
        return float("nan")
    mean_x = sum(x) / n
    mean_y = sum(y) / n
    num    = sum((xi - mean_x) * (yi - mean_y) for xi, yi in zip(x, y))
    den_x  = math.sqrt(sum((xi - mean_x) ** 2 for xi in x))
    den_y  = math.sqrt(sum((yi - mean_y) ** 2 for yi in y))
    if den_x == 0 or den_y == 0:
        return float("nan")
    return num / (den_x * den_y)

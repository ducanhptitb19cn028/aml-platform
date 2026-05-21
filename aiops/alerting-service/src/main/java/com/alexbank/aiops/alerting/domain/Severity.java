package com.yourbank.aiops.alerting.domain;

/**
 * Alert severity mapped from anomaly score.
 *
 * Score thresholds:
 *   >= 0.90 → P1 (critical)
 *   >= 0.75 → P2 (high)
 *   >= 0.50 → P3 (medium)
 *   < 0.50  → P4 (low)
 */
public enum Severity {
    P1, P2, P3, P4;

    public static Severity fromAnomalyScore(double score) {
        if (score >= 0.90) return P1;
        if (score >= 0.75) return P2;
        if (score >= 0.50) return P3;
        return P4;
    }
}

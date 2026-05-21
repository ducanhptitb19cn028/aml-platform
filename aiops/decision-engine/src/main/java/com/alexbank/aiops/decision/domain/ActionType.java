package com.alexbank.aiops.decision.domain;

public enum ActionType {
    SCALE_OUT,
    ROLLBACK,
    RESTART_POD,
    CIRCUIT_BREAK,
    DB_POOL_EXPAND,
    THROTTLE,
    ESCALATE
}

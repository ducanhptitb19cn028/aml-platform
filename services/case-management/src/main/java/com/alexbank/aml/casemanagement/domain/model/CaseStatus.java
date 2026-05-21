package com.yourbank.aml.casemanagement.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * The case lifecycle. Allowed transitions are encoded HERE, not in a service.
 * This is what makes the domain model "rich" rather than anaemic.
 *
 * <pre>
 *   OPEN ──────────► UNDER_INVESTIGATION ──────► ESCALATED
 *    │                    │   ▲                     │
 *    │                    │   │                     │
 *    │                    ▼   │                     ▼
 *    │             PENDING_REVIEW ◄──────────── (any non-terminal)
 *    │                    │
 *    └──────────► CLOSED ◄┘ (terminal)
 * </pre>
 */
public enum CaseStatus {
    OPEN,
    UNDER_INVESTIGATION,
    ESCALATED,
    PENDING_REVIEW,
    CLOSED;

    private Set<CaseStatus> allowedNext;

    static {
        OPEN.allowedNext = EnumSet.of(UNDER_INVESTIGATION, CLOSED);
        UNDER_INVESTIGATION.allowedNext = EnumSet.of(ESCALATED, PENDING_REVIEW, CLOSED);
        ESCALATED.allowedNext = EnumSet.of(PENDING_REVIEW, CLOSED);
        PENDING_REVIEW.allowedNext = EnumSet.of(UNDER_INVESTIGATION, CLOSED);
        CLOSED.allowedNext = EnumSet.noneOf(CaseStatus.class);
    }

    public boolean canTransitionTo(CaseStatus target) {
        return allowedNext.contains(target);
    }

    public boolean isTerminal() {
        return this == CLOSED;
    }
}

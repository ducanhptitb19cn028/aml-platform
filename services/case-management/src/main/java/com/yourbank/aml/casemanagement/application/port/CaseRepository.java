package com.yourbank.aml.casemanagement.application.port;

import com.yourbank.aml.casemanagement.domain.model.Case;
import com.yourbank.aml.casemanagement.domain.model.CaseId;

import java.util.Optional;

/**
 * Outbound port. The application defines what it needs;
 * infrastructure provides it. This inversion is what makes the domain
 * testable without a database.
 */
public interface CaseRepository {
    void save(Case aggregate);
    Optional<Case> findById(CaseId id);
}

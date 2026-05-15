package com.yourbank.aml.casemanagement.infrastructure.api;

import com.yourbank.aml.casemanagement.application.CaseNotFoundException;
import com.yourbank.aml.casemanagement.domain.exception.IllegalCaseTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Domain exceptions map to RFC 7807 problem details.
 * Importantly, the domain throws plain Java exceptions (no Spring) — we
 * translate them at the boundary, not in the domain.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(IllegalCaseTransitionException.class)
    ResponseEntity<Map<String, Object>> conflict(IllegalCaseTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem("invalid-transition", ex));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(problem("invalid-request", ex));
    }

    @ExceptionHandler(CaseNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(CaseNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem("not-found", ex));
    }

    private static Map<String, Object> problem(String code, Exception ex) {
        return Map.of(
                "type", "https://errors.aml.example/" + code,
                "title", code,
                "detail", ex.getMessage(),
                "timestamp", Instant.now().toString()
        );
    }
}

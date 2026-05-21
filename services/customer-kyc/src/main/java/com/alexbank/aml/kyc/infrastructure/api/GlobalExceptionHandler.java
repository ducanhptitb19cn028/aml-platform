package com.yourbank.aml.kyc.infrastructure.api;

import com.yourbank.aml.kyc.application.CustomerNotFoundException;
import com.yourbank.aml.kyc.domain.exception.IllegalVerificationTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(IllegalVerificationTransitionException.class)
    ResponseEntity<Map<String, Object>> conflict(IllegalVerificationTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem("invalid-transition", ex));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(problem("invalid-request", ex));
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(CustomerNotFoundException ex) {
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

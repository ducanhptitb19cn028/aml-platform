package com.yourbank.aml.casemanagement.infrastructure.api;

import com.yourbank.aml.casemanagement.application.CaseApplicationService;
import com.yourbank.aml.casemanagement.application.command.AssignCaseCommand;
import com.yourbank.aml.casemanagement.application.command.CloseCaseCommand;
import com.yourbank.aml.casemanagement.application.command.EscalateCaseCommand;
import com.yourbank.aml.casemanagement.application.command.OpenCaseCommand;
import com.yourbank.aml.casemanagement.domain.model.CaseId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cases")
class CaseController {

    private final CaseApplicationService service;

    CaseController(CaseApplicationService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<Map<String, String>> open(@Valid @RequestBody OpenCaseRequest req) {
        CaseId id = service.openCase(
                new OpenCaseCommand(req.alertId(), req.customerId(), req.riskScore()));
        return ResponseEntity.created(URI.create("/api/v1/cases/" + id.asString()))
                .body(Map.of("id", id.asString()));
    }

    @PostMapping("/{id}/assign")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    void assign(@PathVariable String id, @Valid @RequestBody AssignRequest req) {
        service.assignCase(new AssignCaseCommand(CaseId.of(id), req.investigatorId()));
    }

    @PostMapping("/{id}/escalate")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    void escalate(@PathVariable String id, @Valid @RequestBody EscalateRequest req) {
        service.escalateCase(new EscalateCaseCommand(CaseId.of(id), req.reason()));
    }

    @PostMapping("/{id}/close")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    void close(@PathVariable String id, @Valid @RequestBody CloseRequest req) {
        service.closeCase(new CloseCaseCommand(CaseId.of(id), req.resolution()));
    }

    record OpenCaseRequest(
            @NotBlank String alertId,
            @NotBlank String customerId,
            @Min(0) @Max(100) int riskScore) {}

    record AssignRequest(@NotBlank String investigatorId) {}
    record EscalateRequest(@NotBlank String reason) {}
    record CloseRequest(@NotBlank String resolution) {}
}

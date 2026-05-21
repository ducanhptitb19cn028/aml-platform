package com.alexbank.aml.monitoring.infrastructure.api;

import com.alexbank.aml.monitoring.application.EvaluateTransactionResult;
import com.alexbank.aml.monitoring.application.EvaluateTransactionUseCase;
import com.alexbank.aml.monitoring.application.command.EvaluateTransactionCommand;
import com.alexbank.aml.monitoring.domain.model.Channel;
import com.alexbank.aml.monitoring.domain.model.CountryCode;
import com.alexbank.aml.monitoring.domain.model.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transactions")
class TransactionMonitoringController {

    private final EvaluateTransactionUseCase useCase;
    private final Clock clock;

    TransactionMonitoringController(EvaluateTransactionUseCase useCase, Clock clock) {
        this.useCase = useCase;
        this.clock = clock;
    }

    @PostMapping("/evaluate")
    ResponseEntity<Map<String, Object>> evaluate(@Valid @RequestBody EvaluateRequest req) {
        EvaluateTransactionResult result = useCase.evaluate(new EvaluateTransactionCommand(
                req.customerId(),
                Money.of(req.amount(), req.currency()),
                new CountryCode(req.originCountry()),
                new CountryCode(req.destinationCountry()),
                req.channel(),
                req.occurredAt() != null ? req.occurredAt() : clock.instant()
        ));

        Map<String, Object> body = new HashMap<>();
        body.put("transactionId", result.transactionId().asString());
        body.put("riskScore", result.riskScore());
        body.put("alerted", result.alerted());
        result.alertId().ifPresent(id -> body.put("alertId", id.asString()));

        HttpStatus status = result.alerted() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(body);
    }

    record EvaluateRequest(
            @NotBlank String customerId,
            @NotBlank String amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotBlank @Size(min = 2, max = 2) String originCountry,
            @NotBlank @Size(min = 2, max = 2) String destinationCountry,
            @NotNull Channel channel,
            Instant occurredAt
    ) {}
}

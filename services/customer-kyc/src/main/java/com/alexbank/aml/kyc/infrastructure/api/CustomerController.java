package com.alexbank.aml.kyc.infrastructure.api;

import com.alexbank.aml.kyc.application.CustomerApplicationService;
import com.alexbank.aml.kyc.application.CustomerNotFoundException;
import com.alexbank.aml.kyc.application.CustomerView;
import com.alexbank.aml.kyc.application.command.OnboardCustomerCommand;
import com.alexbank.aml.kyc.application.command.UpdateRiskProfileCommand;
import com.alexbank.aml.kyc.application.command.VerifyCustomerCommand;
import com.alexbank.aml.kyc.domain.model.CustomerId;
import com.alexbank.aml.kyc.domain.model.RiskTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/customers")
class CustomerController {

    private final CustomerApplicationService service;

    CustomerController(CustomerApplicationService service) {
        this.service = service;
    }

    /**
     * The synchronous query endpoint hit by Monitoring and Case
     * Management. This is on the hot path of every transaction
     * evaluation, so latency matters — Pact contracts on both
     * consumers protect the response shape.
     */
    @GetMapping("/{id}")
    CustomerView get(@PathVariable String id) {
        return service.findById(CustomerId.of(id))
                .orElseThrow(() -> new CustomerNotFoundException(CustomerId.of(id)));
    }

    @PostMapping
    ResponseEntity<Map<String, String>> onboard(@Valid @RequestBody OnboardRequest req) {
        CustomerId id = service.onboard(new OnboardCustomerCommand(
                CustomerId.of(req.customerId()),
                req.legalName(),
                req.residencyCountry()
        ));
        return ResponseEntity.created(URI.create("/api/v1/customers/" + id.asString()))
                .body(Map.of("id", id.asString()));
    }

    @PostMapping("/{id}/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verify(@PathVariable String id, @Valid @RequestBody VerifyRequest req) {
        service.verify(new VerifyCustomerCommand(CustomerId.of(id), req.verifiedBy()));
    }

    @PostMapping("/{id}/risk")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateRisk(@PathVariable String id, @Valid @RequestBody UpdateRiskRequest req) {
        service.updateRiskProfile(new UpdateRiskProfileCommand(
                CustomerId.of(id),
                req.newTier(),
                req.politicallyExposed(),
                req.sanctioned(),
                req.reason()
        ));
    }

    record OnboardRequest(
            @NotBlank @Size(max = 64) String customerId,
            @NotBlank @Size(max = 256) String legalName,
            @NotBlank @Size(min = 2, max = 2) String residencyCountry) {}

    record VerifyRequest(@NotBlank String verifiedBy) {}

    record UpdateRiskRequest(
            @NotNull RiskTier newTier,
            boolean politicallyExposed,
            boolean sanctioned,
            @NotBlank String reason) {}
}

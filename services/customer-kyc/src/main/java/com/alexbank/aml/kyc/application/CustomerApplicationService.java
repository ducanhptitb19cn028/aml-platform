package com.alexbank.aml.kyc.application;

import com.alexbank.aml.kyc.application.command.OnboardCustomerCommand;
import com.alexbank.aml.kyc.application.command.UpdateRiskProfileCommand;
import com.alexbank.aml.kyc.application.command.VerifyCustomerCommand;
import com.alexbank.aml.kyc.application.port.CustomerRepository;
import com.alexbank.aml.kyc.application.port.DomainEventPublisher;
import com.alexbank.aml.kyc.domain.model.CountryCode;
import com.alexbank.aml.kyc.domain.model.VerificationStatus;
import com.alexbank.aml.kyc.domain.model.Customer;
import com.alexbank.aml.kyc.domain.model.CustomerId;
import com.alexbank.aml.kyc.domain.model.RiskProfile;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Use cases for the KYC service.
 *
 * The query side (findById) does NOT go through the application service
 * for performance reasons — synchronous lookups happen on the hot path
 * of every transaction evaluation in Monitoring. The controller calls
 * the repository directly. This is a deliberate departure from "all
 * reads go through the use case layer" — the layer would add nothing
 * here except latency.
 */
@Service
public class CustomerApplicationService {

    private final CustomerRepository repository;
    private final DomainEventPublisher eventPublisher;
    private final Tracer tracer;
    private final Counter customersOnboardedCounter;
    private final Counter customersVerifiedCounter;
    private final Counter riskProfileUpdatesCounter;

    public CustomerApplicationService(CustomerRepository repository,
                                      DomainEventPublisher eventPublisher,
                                      Tracer tracer,
                                      MeterRegistry meterRegistry) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.tracer = tracer;
        this.customersOnboardedCounter = Counter.builder("aml.kyc.customers_onboarded")
                .register(meterRegistry);
        this.customersVerifiedCounter = Counter.builder("aml.kyc.customers_verified")
                .register(meterRegistry);
        this.riskProfileUpdatesCounter = Counter.builder("aml.kyc.risk_updates")
                .description("Risk profile changes that emitted a downstream event")
                .register(meterRegistry);
    }

    @Transactional
    @Timed(value = "aml.kyc.onboard")
    public CustomerId onboard(OnboardCustomerCommand cmd) {
        Span span = tracer.nextSpan().name("customer.onboard").start();
        try (var scope = tracer.withSpan(span)) {
            span.tag("customer.id", cmd.customerId().asString());
            span.tag("residency", cmd.residencyCountry());

            Customer c = Customer.onboard(cmd.customerId(), cmd.legalName(),
                    new CountryCode(cmd.residencyCountry()));
            repository.save(c);
            eventPublisher.publish(c.pullDomainEvents());
            customersOnboardedCounter.increment();
            return c.id();
        } finally {
            span.end();
        }
    }

    @Transactional
    @Timed(value = "aml.kyc.verify")
    public void verify(VerifyCustomerCommand cmd) {
        Span span = tracer.nextSpan().name("customer.verify").start();
        try (var scope = tracer.withSpan(span)) {
            span.tag("customer.id", cmd.customerId().asString());

            Customer c = repository.findById(cmd.customerId())
                    .orElseThrow(() -> new CustomerNotFoundException(cmd.customerId()));

            // KYC officers may need to call startVerification first;
            // for the API we accept the simpler "verify" command and
            // promote PENDING customers to IN_PROGRESS automatically.
            if (c.status() == VerificationStatus.PENDING) {
                c.startVerification();
            }
            c.verify(cmd.verifiedBy());

            repository.save(c);
            eventPublisher.publish(c.pullDomainEvents());
            customersVerifiedCounter.increment();
        } finally {
            span.end();
        }
    }

    @Transactional
    @Timed(value = "aml.kyc.update_risk")
    public void updateRiskProfile(UpdateRiskProfileCommand cmd) {
        Span span = tracer.nextSpan().name("customer.update_risk").start();
        try (var scope = tracer.withSpan(span)) {
            span.tag("customer.id", cmd.customerId().asString());
            span.tag("new.tier", cmd.newTier().name());

            Customer c = repository.findById(cmd.customerId())
                    .orElseThrow(() -> new CustomerNotFoundException(cmd.customerId()));

            RiskProfile newProfile = new RiskProfile(
                    cmd.newTier(),
                    cmd.politicallyExposed(),
                    cmd.sanctioned(),
                    c.riskProfile().residencyCountry()
            );

            c.updateRiskProfile(newProfile, cmd.reason());
            repository.save(c);
            var emitted = c.pullDomainEvents();
            eventPublisher.publish(emitted);
            if (!emitted.isEmpty()) {
                riskProfileUpdatesCounter.increment();
            }
        } finally {
            span.end();
        }
    }

    @Timed(value = "aml.kyc.lookup")
    public Optional<CustomerView> findById(CustomerId id) {
        Span span = tracer.nextSpan().name("customer.lookup").start();
        try (var scope = tracer.withSpan(span)) {
            span.tag("customer.id", id.asString());
            return repository.findById(id).map(CustomerView::from);
        } finally {
            span.end();
        }
    }
}

package com.alexbank.aml.kyc.infrastructure.persistence;

import com.alexbank.aml.kyc.application.port.CustomerRepository;
import com.alexbank.aml.kyc.domain.model.CountryCode;
import com.alexbank.aml.kyc.domain.model.Customer;
import com.alexbank.aml.kyc.domain.model.CustomerId;
import com.alexbank.aml.kyc.domain.model.RiskProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

interface CustomerJpaRepository extends JpaRepository<CustomerJpaEntity, String> {}

@Component
class CustomerRepositoryAdapter implements CustomerRepository {

    private final CustomerJpaRepository jpa;

    CustomerRepositoryAdapter(CustomerJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(Customer c) {
        CustomerJpaEntity entity = jpa.findById(c.id().asString())
                .map(existing -> {
                    existing.apply(
                            c.status(),
                            c.riskProfile().tier(),
                            c.riskProfile().politicallyExposed(),
                            c.riskProfile().sanctioned(),
                            c.lastUpdatedAt()
                    );
                    return existing;
                })
                .orElseGet(() -> new CustomerJpaEntity(
                        c.id().asString(),
                        c.legalName(),
                        c.status(),
                        c.riskProfile().tier(),
                        c.riskProfile().politicallyExposed(),
                        c.riskProfile().sanctioned(),
                        c.riskProfile().residencyCountry().value(),
                        c.onboardedAt(),
                        c.lastUpdatedAt()
                ));
        jpa.save(entity);
    }

    @Override
    public Optional<Customer> findById(CustomerId id) {
        return jpa.findById(id.asString()).map(this::toDomain);
    }

    private Customer toDomain(CustomerJpaEntity e) {
        return new Customer(
                CustomerId.of(e.getId()),
                e.getLegalName(),
                e.getStatus(),
                new RiskProfile(
                        e.getRiskTier(),
                        e.isPoliticallyExposed(),
                        e.isSanctioned(),
                        new CountryCode(e.getResidencyCountry())
                ),
                e.getOnboardedAt(),
                e.getLastUpdatedAt()
        );
    }
}

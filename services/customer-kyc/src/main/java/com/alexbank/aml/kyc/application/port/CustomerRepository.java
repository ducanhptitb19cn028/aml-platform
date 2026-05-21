package com.yourbank.aml.kyc.application.port;

import com.yourbank.aml.kyc.domain.model.Customer;
import com.yourbank.aml.kyc.domain.model.CustomerId;

import java.util.Optional;

public interface CustomerRepository {
    void save(Customer customer);
    Optional<Customer> findById(CustomerId id);
}

package com.alexbank.aml.kyc.application.port;

import com.alexbank.aml.kyc.domain.model.Customer;
import com.alexbank.aml.kyc.domain.model.CustomerId;

import java.util.Optional;

public interface CustomerRepository {
    void save(Customer customer);
    Optional<Customer> findById(CustomerId id);
}

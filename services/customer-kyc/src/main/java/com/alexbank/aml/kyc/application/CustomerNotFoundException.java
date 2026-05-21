package com.alexbank.aml.kyc.application;

import com.alexbank.aml.kyc.domain.model.CustomerId;

public class CustomerNotFoundException extends RuntimeException {
    public CustomerNotFoundException(CustomerId id) {
        super("Customer not found: " + id.asString());
    }
}

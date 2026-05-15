package com.yourbank.aml.kyc.application;

import com.yourbank.aml.kyc.domain.model.CustomerId;

public class CustomerNotFoundException extends RuntimeException {
    public CustomerNotFoundException(CustomerId id) {
        super("Customer not found: " + id.asString());
    }
}

package com.alexbank.aml.kyc.application.port;

import com.alexbank.aml.kyc.domain.event.DomainEvent;

import java.util.List;

public interface DomainEventPublisher {
    void publish(List<DomainEvent> events);
}

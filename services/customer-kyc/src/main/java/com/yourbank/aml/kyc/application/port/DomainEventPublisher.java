package com.yourbank.aml.kyc.application.port;

import com.yourbank.aml.kyc.domain.event.DomainEvent;

import java.util.List;

public interface DomainEventPublisher {
    void publish(List<DomainEvent> events);
}

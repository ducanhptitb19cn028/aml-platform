package com.alexbank.aml.casemanagement.application.port;

import com.alexbank.aml.casemanagement.domain.event.DomainEvent;

import java.util.List;

public interface DomainEventPublisher {
    void publish(List<DomainEvent> events);
}

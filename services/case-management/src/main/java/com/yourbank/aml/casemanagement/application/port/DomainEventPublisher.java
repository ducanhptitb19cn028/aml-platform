package com.yourbank.aml.casemanagement.application.port;

import com.yourbank.aml.casemanagement.domain.event.DomainEvent;

import java.util.List;

public interface DomainEventPublisher {
    void publish(List<DomainEvent> events);
}

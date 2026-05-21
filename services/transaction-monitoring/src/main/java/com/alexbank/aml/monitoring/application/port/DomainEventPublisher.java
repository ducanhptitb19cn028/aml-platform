package com.yourbank.aml.monitoring.application.port;

import com.yourbank.aml.monitoring.domain.event.DomainEvent;

import java.util.List;

public interface DomainEventPublisher {
    void publish(List<DomainEvent> events);
}

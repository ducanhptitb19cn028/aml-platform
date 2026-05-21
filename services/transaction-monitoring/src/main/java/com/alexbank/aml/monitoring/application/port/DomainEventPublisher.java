package com.alexbank.aml.monitoring.application.port;

import com.alexbank.aml.monitoring.domain.event.DomainEvent;

import java.util.List;

public interface DomainEventPublisher {
    void publish(List<DomainEvent> events);
}

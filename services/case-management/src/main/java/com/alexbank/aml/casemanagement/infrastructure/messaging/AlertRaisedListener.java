package com.yourbank.aml.casemanagement.infrastructure.messaging;

import com.yourbank.aml.casemanagement.application.CaseApplicationService;
import com.yourbank.aml.casemanagement.application.command.OpenCaseCommand;
import com.yourbank.aml.casemanagement.application.port.ProcessedEventStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Listens for AlertRaised events from Transaction Monitoring and opens
 * a Case in response.
 *
 * Idempotency: each event carries a unique eventId. Before processing,
 * we attempt to insert that eventId into processed_events. If the
 * insert is a no-op (event already seen), we skip the rest. The check
 * and the Case insert run in the SAME transaction — without that, two
 * listener threads could race past the check, both think they're
 * first, and both create a Case.
 *
 * Failures: if Case.open or persistence fails, the exception
 * propagates. Spring Kafka's default error handler retries with
 * backoff, then DLQs. We DO NOT swallow exceptions — silent loss is
 * unacceptable in a regulated domain.
 */
@Component
public class AlertRaisedListener {

    private static final Logger log = LoggerFactory.getLogger(AlertRaisedListener.class);
    private static final String PROCESSOR_ID = "case-management.alert-raised";

    private final CaseApplicationService caseService;
    private final ProcessedEventStore processedEventStore;
    private final Counter alertsReceivedCounter;
    private final Counter casesOpenedFromAlertCounter;
    private final Counter duplicatesSkippedCounter;

    AlertRaisedListener(CaseApplicationService caseService,
                        ProcessedEventStore processedEventStore,
                        MeterRegistry meterRegistry) {
        this.caseService = caseService;
        this.processedEventStore = processedEventStore;
        this.alertsReceivedCounter = Counter.builder("aml.alerts.received")
                .description("AlertRaised messages received from Kafka")
                .register(meterRegistry);
        this.casesOpenedFromAlertCounter = Counter.builder("aml.cases.opened_from_alert")
                .description("Cases opened from a new (non-duplicate) alert")
                .register(meterRegistry);
        this.duplicatesSkippedCounter = Counter.builder("aml.alerts.duplicates_skipped")
                .description("Duplicate AlertRaised events skipped via dedup")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = "${aml.kafka.topic.alerts:aml.alerts.events}",
            groupId = "case-management",
            containerFactory = "alertRaisedListenerContainerFactory"
    )
    @Transactional
    public void onAlertRaised(Map<String, Object> envelope) {
        alertsReceivedCounter.increment();

        AlertRaisedMessage msg = AlertRaisedMessage.fromEnvelope(envelope);

        // Idempotency check + Case creation must be one atomic transaction.
        // Spring's @Transactional on this method ensures both the
        // processed_events insert and the Case insert commit together.
        boolean isNew = processedEventStore.markIfNotProcessed(msg.eventId(), PROCESSOR_ID);
        if (!isNew) {
            duplicatesSkippedCounter.increment();
            log.info("Skipping duplicate AlertRaised: eventId={}, alertId={}",
                    msg.eventId(), msg.alertId());
            return;
        }

        log.info("Processing AlertRaised: eventId={}, alertId={}, customer={}, risk={}",
                msg.eventId(), msg.alertId(), msg.customerId(), msg.riskScore());

        caseService.openCase(new OpenCaseCommand(
                msg.alertId(),
                msg.customerId(),
                msg.riskScore()
        ));
        casesOpenedFromAlertCounter.increment();
    }
}

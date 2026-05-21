package com.yourbank.aml.monitoring.application;

import com.yourbank.aml.monitoring.application.command.EvaluateTransactionCommand;
import com.yourbank.aml.monitoring.application.port.AlertRepository;
import com.yourbank.aml.monitoring.application.port.DomainEventPublisher;
import com.yourbank.aml.monitoring.application.port.TransactionRepository;
import com.yourbank.aml.monitoring.domain.model.Alert;
import com.yourbank.aml.monitoring.domain.model.Transaction;
import com.yourbank.aml.monitoring.domain.model.TransactionId;
import com.yourbank.aml.monitoring.domain.rule.RuleContext;
import com.yourbank.aml.monitoring.domain.rule.RuleEngine;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Use case: a transaction has occurred — evaluate it and decide whether to
 * raise an alert.
 *
 * Observability strategy:
 *   - One span per evaluation, tagged with customer + risk band
 *   - One span event per fired rule (these become research features)
 *   - Counter per rule fired (low cardinality: rule.id only)
 *   - Histogram on evaluation latency
 */
@Service
public class EvaluateTransactionUseCase {

    private static final Duration HISTORY_WINDOW = Duration.ofDays(7);

    private final TransactionRepository transactionRepository;
    private final AlertRepository alertRepository;
    private final DomainEventPublisher eventPublisher;
    private final RuleEngine ruleEngine;
    private final Tracer tracer;
    private final MeterRegistry meterRegistry;

    private final Counter transactionsEvaluatedCounter;
    private final Counter alertsRaisedCounter;

    public EvaluateTransactionUseCase(TransactionRepository transactionRepository,
                                      AlertRepository alertRepository,
                                      DomainEventPublisher eventPublisher,
                                      RuleEngine ruleEngine,
                                      Tracer tracer,
                                      MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.alertRepository = alertRepository;
        this.eventPublisher = eventPublisher;
        this.ruleEngine = ruleEngine;
        this.tracer = tracer;
        this.meterRegistry = meterRegistry;

        this.transactionsEvaluatedCounter = Counter.builder("aml.transactions.evaluated")
                .description("Transactions evaluated by the rule engine")
                .register(meterRegistry);
        this.alertsRaisedCounter = Counter.builder("aml.alerts.raised")
                .description("Alerts raised from evaluations")
                .register(meterRegistry);
    }

    @Transactional
    @Timed(value = "aml.transaction.evaluate", description = "Time to evaluate a transaction")
    public EvaluateTransactionResult evaluate(EvaluateTransactionCommand cmd) {
        Span span = tracer.nextSpan().name("transaction.evaluate").start();
        try (var scope = tracer.withSpan(span)) {
            Transaction tx = new Transaction(
                    TransactionId.generate(),
                    cmd.customerId(), cmd.amount(),
                    cmd.originCountry(), cmd.destinationCountry(),
                    cmd.channel(), cmd.occurredAt()
            );
            span.tag("transaction.id", tx.id().asString());
            span.tag("customer.id", tx.customerId());
            span.tag("amount.currency", tx.amount().currency().getCurrencyCode());
            span.tag("channel", tx.channel().name());
            span.tag("crosses.border", String.valueOf(tx.crossesBorder()));

            transactionRepository.save(tx);
            transactionsEvaluatedCounter.increment();

            List<Transaction> history = transactionRepository
                    .findByCustomerWithin(tx.customerId(), HISTORY_WINDOW);

            RuleContext ctx = new RuleContext(tx, history, HISTORY_WINDOW);
            RuleEngine.EngineResult result = ruleEngine.evaluate(ctx);

            // One span event + one metric per fired rule
            result.firedVerdicts().forEach(v -> {
                span.event("rule.fired." + v.ruleId());
                meterRegistry.counter("aml.rule.fired",
                        List.of(Tag.of("rule.id", v.ruleId()))).increment();
            });
            span.tag("risk.score", String.valueOf(result.combinedRiskScore()));

            if (!result.anyFired()) {
                span.tag("alert.raised", "false");
                return new EvaluateTransactionResult(tx.id(), result.combinedRiskScore(), Optional.empty());
            }

            Alert alert = Alert.raise(
                    tx.id(), tx.customerId(),
                    result.combinedRiskScore(),
                    result.firedVerdicts()
            );
            alertRepository.save(alert);
            eventPublisher.publish(alert.pullDomainEvents());
            alertsRaisedCounter.increment();

            span.tag("alert.raised", "true");
            span.tag("alert.id", alert.id().asString());
            return new EvaluateTransactionResult(
                    tx.id(), result.combinedRiskScore(), Optional.of(alert.id()));
        } catch (RuntimeException ex) {
            span.tag("error", "true");
            span.tag("error.kind", ex.getClass().getSimpleName());
            throw ex;
        } finally {
            span.end();
        }
    }
}

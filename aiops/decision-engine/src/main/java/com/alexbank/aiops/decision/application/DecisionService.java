package com.yourbank.aiops.decision.application;

import com.yourbank.aiops.decision.domain.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class DecisionService {

    /**
     * Evaluates an incident and produces a remediation decision based on
     * blast-radius gating and confidence thresholds.
     */
    public RemediationDecision decide(Incident incident) {
        BlastRadius blastRadius = computeBlastRadius(incident.affectedServices());
        String      targetService = resolveTargetService(incident);

        ActionType  action;
        boolean     autoExecute;
        String      justification;

        double confidence        = incident.confidence();
        int    breachEtaMinutes  = incident.breachEtaMinutes();

        if (confidence > 0.90 && blastRadius == BlastRadius.LOW) {
            autoExecute = true;
            if (breachEtaMinutes < 5) {
                action        = ActionType.SCALE_OUT;
                justification = String.format(
                        "High confidence (%.2f) + LOW blast radius + imminent breach in %d min → SCALE_OUT",
                        confidence, breachEtaMinutes);
            } else {
                action        = ActionType.RESTART_POD;
                justification = String.format(
                        "High confidence (%.2f) + LOW blast radius + breach ETA %d min → RESTART_POD",
                        confidence, breachEtaMinutes);
            }
        } else if (confidence > 0.75 && blastRadius == BlastRadius.MEDIUM) {
            autoExecute   = true;
            action        = ActionType.SCALE_OUT;
            justification = String.format(
                    "Good confidence (%.2f) + MEDIUM blast radius → SCALE_OUT",
                    confidence);
        } else {
            autoExecute   = false;
            action        = ActionType.ESCALATE;
            justification = String.format(
                    "Confidence %.2f or blast radius %s requires human review → ESCALATE",
                    confidence, blastRadius);
        }

        RemediationDecision decision = new RemediationDecision(
                UUID.randomUUID().toString(),
                incident.incidentId(),
                action,
                targetService,
                confidence,
                blastRadius,
                autoExecute,
                justification,
                Instant.now()
        );

        log.info("Decision incidentId={} action={} autoExecute={} blastRadius={} target={}",
                incident.incidentId(), action, autoExecute, blastRadius, targetService);
        return decision;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private BlastRadius computeBlastRadius(List<String> affectedServices) {
        if (affectedServices == null || affectedServices.isEmpty()) {
            return BlastRadius.LOW;
        }
        int size = affectedServices.size();
        if (size == 1)      return BlastRadius.LOW;
        if (size <= 2)      return BlastRadius.MEDIUM;
        return BlastRadius.HIGH;
    }

    private String resolveTargetService(Incident incident) {
        List<RootCauseEntry> ranking = incident.rootCauseRanking();
        if (ranking != null && !ranking.isEmpty()) {
            // Top-ranked component is "service.metricName" — keep only the service part
            return ranking.stream()
                    .max((a, b) -> Double.compare(a.weight(), b.weight()))
                    .map(e -> {
                        String comp = e.component();
                        int dot = comp.indexOf('.');
                        return dot > 0 ? comp.substring(0, dot) : comp;
                    })
                    .orElse(defaultService(incident));
        }
        return defaultService(incident);
    }

    private String defaultService(Incident incident) {
        List<String> services = incident.affectedServices();
        if (services != null && !services.isEmpty()) {
            return services.get(0);
        }
        return "unknown";
    }
}

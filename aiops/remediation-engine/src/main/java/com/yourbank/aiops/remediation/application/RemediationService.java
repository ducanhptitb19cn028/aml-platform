package com.yourbank.aiops.remediation.application;

import com.yourbank.aiops.remediation.domain.RemediationRecord;
import com.yourbank.aiops.remediation.domain.RemediationStatus;
import com.yourbank.aiops.remediation.infrastructure.kubernetes.KubernetesActuator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RemediationService {

    private static final Set<String> AIOPS_SERVICES = Set.of(
            "telemetry-collector", "stream-processor", "decision-engine",
            "remediation-engine",  "alerting-service",  "feedback-service",
            "ml-engine",           "llm-engine"
    );

    @Value("${aiops.remediation.namespace.aml:aml}")
    private String amlNamespace;

    @Value("${aiops.remediation.namespace.aiops:aiops}")
    private String aiopsNamespace;

    @Value("${aiops.remediation.scale-out-replicas:2}")
    private int scaleOutReplicas;

    private final KubernetesActuator kubernetesActuator;

    private String resolveNamespace(String service) {
        return AIOPS_SERVICES.contains(service) ? aiopsNamespace : amlNamespace;
    }

    public RemediationRecord execute(RemediationDecision decision) {
        if (!decision.autoExecute()) {
            log.info("Decision decisionId={} autoExecute=false → VETOED", decision.decisionId());
            return buildRecord(decision, RemediationStatus.VETOED, "autoExecute=false", null, amlNamespace);
        }

        String targetService = decision.targetService();
        String ns            = resolveNamespace(targetService);
        String preState      = "replicas=current";

        log.info("Executing action={} for service={} ns={} decisionId={}",
                decision.action(), targetService, ns, decision.decisionId());

        try {
            switch (decision.action()) {
                case "SCALE_OUT" -> {
                    kubernetesActuator.scaleOut(targetService, ns, scaleOutReplicas);
                    return buildRecord(decision, RemediationStatus.COMPLETED, preState,
                            "minReplicas += " + scaleOutReplicas, ns);
                }
                case "RESTART_POD" -> {
                    kubernetesActuator.restartPod(targetService, ns);
                    kubernetesActuator.cleanupCrashedPods(targetService, ns);
                    return buildRecord(decision, RemediationStatus.COMPLETED, preState,
                            "rolling restart triggered; crashed pods cleaned up", ns);
                }
                case "ROLLBACK" -> {
                    kubernetesActuator.rollback(targetService, ns);
                    return buildRecord(decision, RemediationStatus.COMPLETED, preState,
                            "rollback instruction emitted", ns);
                }
                case "THROTTLE" -> {
                    kubernetesActuator.throttle(targetService, ns);
                    return buildRecord(decision, RemediationStatus.COMPLETED, preState,
                            "rate-limit annotations patched", ns);
                }
                case "ESCALATE" -> {
                    log.info("ESCALATE action for decisionId={} — no Kubernetes operation", decision.decisionId());
                    return buildRecord(decision, RemediationStatus.VETOED, preState, "escalated to on-call", ns);
                }
                default -> {
                    log.warn("Unknown action={} for decisionId={}", decision.action(), decision.decisionId());
                    return buildRecord(decision, RemediationStatus.VETOED, preState, "unknown action type", ns);
                }
            }
        } catch (Exception e) {
            log.error("Remediation FAILED for action={} service={} ns={}: {}",
                    decision.action(), targetService, ns, e.getMessage(), e);
            return buildRecord(decision, RemediationStatus.FAILED, preState, "error: " + e.getMessage(), ns);
        }
    }

    private RemediationRecord buildRecord(RemediationDecision decision, RemediationStatus status,
                                          String preState, String postState, String resolvedNamespace) {
        return new RemediationRecord(
                UUID.randomUUID().toString(),
                decision.decisionId(),
                decision.incidentId(),
                decision.action(),
                decision.targetService(),
                resolvedNamespace,
                status,
                preState,
                postState,
                Instant.now()
        );
    }
}

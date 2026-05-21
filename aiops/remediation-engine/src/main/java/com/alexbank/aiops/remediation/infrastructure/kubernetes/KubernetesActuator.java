package com.alexbank.aiops.remediation.infrastructure.kubernetes;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class KubernetesActuator {

    private final KubernetesClient client;

    /**
     * Bumps the HPA minReplicas for the given service deployment by additionalReplicas.
     */
    public void scaleOut(String service, String namespace, int additionalReplicas) {
        try {
            HorizontalPodAutoscaler hpa = client.autoscaling().v2()
                    .horizontalPodAutoscalers()
                    .inNamespace(namespace)
                    .withName(service)
                    .get();

            if (hpa == null) {
                log.warn("HPA not found for service={} namespace={} — attempting direct Deployment scale",
                        service, namespace);
                scaleDeploymentDirectly(service, namespace, additionalReplicas);
                return;
            }

            int current     = hpa.getSpec().getMinReplicas() != null
                    ? hpa.getSpec().getMinReplicas() : 1;
            int newMin       = current + additionalReplicas;
            int maxReplicas  = hpa.getSpec().getMaxReplicas();
            if (newMin > maxReplicas) {
                newMin = maxReplicas;
                log.warn("Clamping minReplicas to maxReplicas={} for service={}", maxReplicas, service);
            }

            final int targetMin = newMin;
            client.autoscaling().v2()
                    .horizontalPodAutoscalers()
                    .inNamespace(namespace)
                    .withName(service)
                    .edit(h -> {
                        h.getSpec().setMinReplicas(targetMin);
                        return h;
                    });

            log.info("ScaleOut service={} ns={} minReplicas {} → {}", service, namespace, current, newMin);
        } catch (Exception e) {
            log.error("ScaleOut failed for service={} ns={}: {}", service, namespace, e.getMessage(), e);
            throw new RuntimeException("ScaleOut failed: " + e.getMessage(), e);
        }
    }

    /**
     * Triggers a rolling restart of the Deployment.
     */
    public void restartPod(String service, String namespace) {
        try {
            client.apps().deployments()
                    .inNamespace(namespace)
                    .withName(service)
                    .rolling()
                    .restart();
            log.info("RollingRestart triggered for service={} ns={}", service, namespace);
        } catch (Exception e) {
            log.error("RestartPod failed for service={} ns={}: {}", service, namespace, e.getMessage(), e);
            throw new RuntimeException("RestartPod failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the previous Helm revision annotation and logs the rollback instruction.
     * Actual Helm rollback must be triggered externally (Helm CLI / ArgoCD).
     */
    public void rollback(String service, String namespace) {
        try {
            var deployment = client.apps().deployments()
                    .inNamespace(namespace)
                    .withName(service)
                    .get();

            if (deployment == null) {
                log.warn("Deployment not found for rollback: service={} ns={}", service, namespace);
                return;
            }

            Map<String, String> annotations = deployment.getMetadata().getAnnotations();
            String helmRelease  = annotations != null
                    ? annotations.getOrDefault("meta.helm.sh/release-name", service) : service;
            String currentRev   = annotations != null
                    ? annotations.getOrDefault("deployment.kubernetes.io/revision", "unknown") : "unknown";

            log.warn("ROLLBACK requested for service={} helmRelease={} currentRevision={}. " +
                    "Execute: helm rollback {} --namespace {}",
                    service, helmRelease, currentRev, helmRelease, namespace);
            // Helm rollback triggered externally — this service emits the audit record only
        } catch (Exception e) {
            log.error("Rollback annotation lookup failed for service={} ns={}: {}",
                    service, namespace, e.getMessage(), e);
            throw new RuntimeException("Rollback failed: " + e.getMessage(), e);
        }
    }

    /**
     * Patches the Ingress with nginx rate-limit annotations to throttle traffic.
     */
    public void throttle(String service, String namespace) {
        try {
            var ingress = client.network().v1().ingresses()
                    .inNamespace(namespace)
                    .withName(service)
                    .get();

            if (ingress == null) {
                log.warn("Ingress not found for throttle: service={} ns={} — skipping", service, namespace);
                return;
            }

            client.network().v1().ingresses()
                    .inNamespace(namespace)
                    .withName(service)
                    .edit(ig -> {
                        Map<String, String> ann = ig.getMetadata().getAnnotations();
                        ann.put("nginx.ingress.kubernetes.io/limit-rps", "100");
                        ann.put("nginx.ingress.kubernetes.io/limit-connections", "50");
                        ig.getMetadata().setAnnotations(ann);
                        return ig;
                    });

            log.info("Throttle applied to ingress service={} ns={}", service, namespace);
        } catch (Exception e) {
            log.error("Throttle failed for service={} ns={}: {}", service, namespace, e.getMessage(), e);
            throw new RuntimeException("Throttle failed: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes pods for the given service that are in a terminal-error state
     * (Failed, CrashLoopBackOff, OOMKilled, Evicted). Call this after a rolling
     * restart so stale crashed pods don't accumulate in the namespace.
     */
    public void cleanupCrashedPods(String service, String namespace) {
        try {
            List<Pod> pods = client.pods()
                    .inNamespace(namespace)
                    .withLabel("app", service)
                    .list()
                    .getItems();

            Set<String> crashReasons = Set.of("CrashLoopBackOff", "OOMKilled", "Error",
                                              "CreateContainerConfigError", "ImagePullBackOff");
            int removed = 0;
            for (Pod pod : pods) {
                String podName = pod.getMetadata().getName();
                String phase   = pod.getStatus() != null ? pod.getStatus().getPhase() : "";
                String reason  = pod.getStatus() != null ? pod.getStatus().getReason() : "";

                boolean isFailed  = "Failed".equals(phase);
                boolean isEvicted = "Evicted".equals(reason);
                boolean isCrashed = pod.getStatus() != null
                        && pod.getStatus().getContainerStatuses() != null
                        && pod.getStatus().getContainerStatuses().stream()
                              .anyMatch(cs -> cs.getState() != null
                                          && cs.getState().getWaiting() != null
                                          && crashReasons.contains(cs.getState().getWaiting().getReason()));

                if (isFailed || isEvicted || isCrashed) {
                    client.pods().inNamespace(namespace).withName(podName)
                          .withGracePeriod(0L).delete();
                    log.info("Cleaned up crashed pod={} phase={} reason={} ns={}", podName, phase, reason, namespace);
                    removed++;
                }
            }
            if (removed > 0) {
                log.info("Removed {} crashed pod(s) for service={} ns={}", removed, service, namespace);
            }
        } catch (Exception e) {
            log.warn("cleanupCrashedPods failed for service={} ns={}: {}", service, namespace, e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void scaleDeploymentDirectly(String service, String namespace, int additionalReplicas) {
        var deployment = client.apps().deployments()
                .inNamespace(namespace)
                .withName(service)
                .get();

        if (deployment == null) {
            log.warn("Deployment also not found for service={} ns={}", service, namespace);
            return;
        }

        int current = deployment.getSpec().getReplicas() != null
                ? deployment.getSpec().getReplicas() : 1;
        int target  = current + additionalReplicas;

        client.apps().deployments()
                .inNamespace(namespace)
                .withName(service)
                .scale(target, true);

        log.info("Direct Deployment scale service={} ns={} {} → {}", service, namespace, current, target);
    }
}

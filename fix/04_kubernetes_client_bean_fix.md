# Fix 04 — remediation-engine: KubernetesClient Bean Not Found

## Problem
`remediation-engine` crashed at startup with:
```
Parameter 0 of constructor in KubernetesActuator required a bean of type
'io.fabric8.kubernetes.client.KubernetesClient' that could not be found.
```

## Root Cause
The `io.fabric8:kubernetes-client` library does **not** provide Spring Boot autoconfiguration.
Unlike other Spring Boot starters, fabric8's raw Kubernetes client requires an explicit
`@Bean` declaration — there is no `@ConditionalOnMissingBean` autoconfiguration for it.

## Fix

### Created `KubernetesConfig.java`
**Path:** `aiops/remediation-engine/src/main/java/com/alexbank/aiops/remediation/infrastructure/kubernetes/KubernetesConfig.java`

```java
package com.alexbank.aiops.remediation.infrastructure.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KubernetesConfig {

    @Bean
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
```

`KubernetesClientBuilder().build()` auto-discovers the in-cluster service account credentials
from the pod's mounted `/var/run/secrets/kubernetes.io/serviceaccount/` token.

The remediation-engine's `ServiceAccount` in `aiops.yml` is bound to `aiops-remediation-role`
which grants `get/list/patch/update` on Deployments, HPAs, and Ingresses in the `aml` namespace.

## Files Changed
- `aiops/remediation-engine/src/main/java/com/alexbank/aiops/remediation/infrastructure/kubernetes/KubernetesConfig.java` (new)

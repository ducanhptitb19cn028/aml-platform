# Fix 03 — Spring Boot Fat JAR Not Produced

## Problem
All 3 AML services crashed immediately with:
```
no main manifest attribute, in /app/app.jar
```
The `app.jar` inside the container was only ~60 KB (a plain class JAR with no
`Main-Class` manifest entry).

## Root Cause — Two independent issues

### Issue A: Missing `repackage` execution in parent POM
The project uses a **custom parent POM** (not `spring-boot-starter-parent`). Without
`spring-boot-starter-parent`, the `spring-boot-maven-plugin`'s `repackage` goal is
**not automatically bound** to the `package` lifecycle phase. So `mvn package` only
produced the thin class JAR (~60 KB), never the fat/executable JAR.

### Issue B: `COPY *.jar` picking up the plain JAR
Spring Boot 3.x `spring-boot-maven-plugin` produces two JARs:
- `app-0.1.0.jar` — the fat/executable JAR
- `app-0.1.0-plain.jar` — the original Maven JAR (not executable)

The Dockerfile glob `COPY target/*.jar /app/app.jar` matched both. With multiple
files matched, Docker's behavior is unpredictable (can copy the wrong one or fail).

## Fix

### A — Added `repackage` execution to parent `pom.xml`
```xml
<pluginManagement>
  <plugins>
    <plugin>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-maven-plugin</artifactId>
      <version>${spring-boot.version}</version>
      <executions>
        <execution>
          <goals>
            <goal>repackage</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</pluginManagement>
```
All three child modules inherit this execution. `mvn package` now produces the fat JAR.

### B — Added plain JAR removal in each service Dockerfile
```dockerfile
RUN mvn -B -DskipTests -pl services/case-management package && \
    rm -f /workspace/services/case-management/target/*-plain.jar
```
Applied to all three: `case-management`, `transaction-monitoring`, `customer-kyc`.
Same fix applied to `aiops/remediation-engine/Dockerfile`:
```dockerfile
RUN mvn -B -DskipTests -pl remediation-engine -am package && \
    rm -f /build/remediation-engine/target/*-plain.jar
```

### C — Changed `imagePullPolicy` to `Always` in K8s manifests
`imagePullPolicy: IfNotPresent` caused Kubernetes to reuse the old cached image even
after pushing a new build with the same tag. Changed to `Always` for all custom services
in both `aml-platform.yml` and `aiops.yml`.

## Files Changed
- `pom.xml` — added `repackage` execution to `pluginManagement`
- `services/case-management/Dockerfile`
- `services/transaction-monitoring/Dockerfile`
- `services/customer-kyc/Dockerfile`
- `aiops/remediation-engine/Dockerfile`
- `infrastructure/k8s/aml-platform.yml` — `imagePullPolicy: Always` for all 3 services
- `infrastructure/k8s/aiops.yml` — `imagePullPolicy: Always` for all 7 custom services

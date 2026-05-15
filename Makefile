.PHONY: help build test mutation arch verify \
        infra-up infra-down aiops-infra-up aiops-infra-down \
        image-aml image-cases image-monitoring image-kyc \
        image-aiops image-aiops-collector image-aiops-stream image-aiops-ml \
        image-aiops-llm image-aiops-decision image-aiops-remediation \
        image-aiops-alerting image-aiops-feedback image-aiops-agent image-dashboard \
        push-aml push-aiops push-dashboard \
        cluster-up cluster-down \
        k8s-data k8s-topics k8s-monitoring \
        k8s-aml k8s-aiops k8s-dashboard k8s-agent k8s-full \
        k8s-status k8s-delete k8s-ollama-pull \
        pf-aml pf-aiops pf-agent pf-dashboard pf-obs \
        contract deploy

# ─────────────────────────────────────────────────────────────────────────────
#  Variables
# ─────────────────────────────────────────────────────────────────────────────
REGISTRY      := localhost:5001
AML_NS        := aml
AIOPS_NS      := aiops
DATA_NS       := data
MONITORING_NS := monitoring

AML_VERSION   := 0.1.0
KYC_VERSION   := 0.3.0
AIOPS_VERSION := 0.1.0

# ─────────────────────────────────────────────────────────────────────────────
#  Help
# ─────────────────────────────────────────────────────────────────────────────
help:
	@echo ""
	@echo "AML + AIOps Platform — make targets"
	@echo ""
	@echo "── Java build / test ──────────────────────────────────────────"
	@echo "  make build              Compile all AML modules"
	@echo "  make test               Run unit + property + arch tests"
	@echo "  make contract           Run all Pact consumer + provider tests"
	@echo "  make mutation           Run Pitest mutation testing"
	@echo "  make arch               Run only ArchUnit tests"
	@echo "  make verify             Full quality gate (build + test + mutation)"
	@echo ""
	@echo "── Local Docker-compose infra ─────────────────────────────────"
	@echo "  make infra-up           Postgres×3 + Kafka + Prometheus + Tempo + Loki"
	@echo "  make infra-down         Tear down AML infra containers"
	@echo "  make aiops-infra-up     Start AIOps supplementary services (ES, MLflow, ML engine, Dashboard)"
	@echo "  make aiops-infra-down   Stop AIOps supplementary services"
	@echo ""
	@echo "── Container images ───────────────────────────────────────────"
	@echo "  make image-aml          Build all 3 AML service images"
	@echo "  make image-cases        Build case-management image"
	@echo "  make image-monitoring   Build transaction-monitoring image"
	@echo "  make image-kyc          Build customer-kyc image"
	@echo "  make image-aiops        Build all 8 AIOps service images (incl. agent-service)"
	@echo "  make image-dashboard    Build React dashboard image"
	@echo "  make push-aml           Push AML images to local registry"
	@echo "  make push-aiops         Push AIOps images to local registry"
	@echo "  make push-dashboard     Push dashboard image to local registry"
	@echo ""
	@echo "── Kubernetes ─────────────────────────────────────────────────"
	@echo "  make cluster-up         Start local registry + observability stack (Docker Desktop K8s)"
	@echo "  make cluster-down       Remove observability stack + local registry"
	@echo "  make k8s-data           Deploy Postgres×3 + Kafka (Bitnami Helm)"
	@echo "  make k8s-topics         Create required Kafka topics"
	@echo "  make k8s-monitoring     Deploy Prometheus + Grafana + Tempo + Loki"
	@echo "  make k8s-aml            Deploy AML platform (aml namespace)"
	@echo "  make k8s-aiops          Deploy AIOps services (aiops namespace)"
	@echo "  make k8s-dashboard      Deploy React dashboard (aiops namespace)"
	@echo "  make k8s-full           Full end-to-end deployment (all of the above)"
	@echo "  make k8s-status         Show pod/svc status for all namespaces"
	@echo "  make k8s-delete         Delete AML + AIOps + data namespaces"
	@echo ""
	@echo "── Port-forwards ──────────────────────────────────────────────"
	@echo "  make pf-aml             Forward AML services (8080 8081 8082)"
	@echo "  make pf-aiops           Forward AIOps services (9001-9006, 8000)"
	@echo "  make pf-agent           Forward AMLOps Agent chatbot -> localhost:9007"
	@echo "  make pf-dashboard       Forward React dashboard -> localhost:3001"
	@echo "  make pf-obs             Forward Grafana(3000) + Prometheus(9090) + Loki(3100)"
	@echo ""

# ─────────────────────────────────────────────────────────────────────────────
#  Java build / test
# ─────────────────────────────────────────────────────────────────────────────
build:
	./mvnw -B -DskipTests package

test:
	./mvnw -B test

contract:
	./mvnw -B -pl services/case-management test -Dtest='*ConsumerPactTest'
	./mvnw -B -pl services/transaction-monitoring test -Dtest='*ConsumerPactTest'
	./mvnw -B -pl services/transaction-monitoring test -Dtest='*ProducerPactTest'
	./mvnw -B -pl services/customer-kyc test -Dtest='*ProviderPactTest'

mutation:
	./mvnw -B -pl services/case-management test org.pitest:pitest-maven:mutationCoverage
	./mvnw -B -pl services/transaction-monitoring test org.pitest:pitest-maven:mutationCoverage
	./mvnw -B -pl services/customer-kyc test org.pitest:pitest-maven:mutationCoverage

arch:
	./mvnw -B test -Dtest='HexagonalArchitectureTest'

verify: build test mutation

# ─────────────────────────────────────────────────────────────────────────────
#  Local Docker-compose infra
# ─────────────────────────────────────────────────────────────────────────────
infra-up:
	docker compose -f infrastructure/docker-compose.yml up -d

infra-down:
	docker compose -f infrastructure/docker-compose.yml down -v

aiops-infra-up: infra-up
	docker compose -f infrastructure/aiops/docker-compose.yml up -d

aiops-infra-down:
	docker compose -f infrastructure/aiops/docker-compose.yml down -v

# ─────────────────────────────────────────────────────────────────────────────
#  Container images — AML (build context = project root)
# ─────────────────────────────────────────────────────────────────────────────
image-cases:
	docker build -f services/case-management/Dockerfile \
		-t $(REGISTRY)/case-management:$(AML_VERSION) .

image-monitoring:
	docker build -f services/transaction-monitoring/Dockerfile \
		-t $(REGISTRY)/transaction-monitoring:$(AML_VERSION) .

image-kyc:
	docker build -f services/customer-kyc/Dockerfile \
		-t $(REGISTRY)/customer-kyc:$(KYC_VERSION) .

image-aml: image-cases image-monitoring image-kyc

# ─────────────────────────────────────────────────────────────────────────────
#  Container images — AIOps (build context = aiops/)
# ─────────────────────────────────────────────────────────────────────────────
image-aiops-collector:
	docker build -f aiops/telemetry-collector/Dockerfile \
		-t $(REGISTRY)/telemetry-collector:$(AIOPS_VERSION) aiops/

image-aiops-stream:
	docker build -f aiops/stream-processor/Dockerfile \
		-t $(REGISTRY)/stream-processor:$(AIOPS_VERSION) aiops/

image-aiops-ml:
	docker build -f ml/ml-engine/Dockerfile \
		-t $(REGISTRY)/ml-engine:$(AIOPS_VERSION) ml/ml-engine/

image-aiops-decision:
	docker build -f aiops/decision-engine/Dockerfile \
		-t $(REGISTRY)/decision-engine:$(AIOPS_VERSION) aiops/

image-aiops-remediation:
	docker build -f aiops/remediation-engine/Dockerfile \
		-t $(REGISTRY)/remediation-engine:$(AIOPS_VERSION) aiops/

image-aiops-alerting:
	docker build -f aiops/alerting-service/Dockerfile \
		-t $(REGISTRY)/alerting-service:$(AIOPS_VERSION) aiops/

image-aiops-feedback:
	docker build -f aiops/feedback-service/Dockerfile \
		-t $(REGISTRY)/feedback-service:$(AIOPS_VERSION) aiops/

image-aiops-llm:
	docker build -f ml/llm-engine/Dockerfile \
		-t $(REGISTRY)/llm-engine:$(AIOPS_VERSION) ml/llm-engine/

image-aiops-agent:
	docker build -t $(REGISTRY)/agent-service:$(AIOPS_VERSION) aiops/agent-service/

image-aiops: image-aiops-collector image-aiops-stream image-aiops-ml \
             image-aiops-llm image-aiops-decision image-aiops-remediation \
             image-aiops-alerting image-aiops-feedback image-aiops-agent

# ─────────────────────────────────────────────────────────────────────────────
#  Container image — React dashboard (build context = dashboard/)
# ─────────────────────────────────────────────────────────────────────────────
image-dashboard:
	docker build -f dashboard/Dockerfile \
		-t $(REGISTRY)/aiops-dashboard:$(AIOPS_VERSION) dashboard/

# ─────────────────────────────────────────────────────────────────────────────
#  Push to local registry
# ─────────────────────────────────────────────────────────────────────────────
push-aml:
	docker push $(REGISTRY)/case-management:$(AML_VERSION)
	docker push $(REGISTRY)/transaction-monitoring:$(AML_VERSION)
	docker push $(REGISTRY)/customer-kyc:$(KYC_VERSION)

push-aiops:
	docker push $(REGISTRY)/telemetry-collector:$(AIOPS_VERSION)
	docker push $(REGISTRY)/stream-processor:$(AIOPS_VERSION)
	docker push $(REGISTRY)/ml-engine:$(AIOPS_VERSION)
	docker push $(REGISTRY)/llm-engine:$(AIOPS_VERSION)
	docker push $(REGISTRY)/decision-engine:$(AIOPS_VERSION)
	docker push $(REGISTRY)/remediation-engine:$(AIOPS_VERSION)
	docker push $(REGISTRY)/alerting-service:$(AIOPS_VERSION)
	docker push $(REGISTRY)/feedback-service:$(AIOPS_VERSION)
	docker push $(REGISTRY)/agent-service:$(AIOPS_VERSION)

push-dashboard:
	docker push $(REGISTRY)/aiops-dashboard:$(AIOPS_VERSION)

# ─────────────────────────────────────────────────────────────────────────────
#  Kubernetes — cluster lifecycle
# ─────────────────────────────────────────────────────────────────────────────
cluster-up:
	@echo NOTE: If helm.exe is blocked by Application Control, run bootstrap.ps1 directly
	@echo from an elevated Admin PowerShell instead of this make target.
	powershell -ExecutionPolicy Bypass -File infrastructure/scripts/bootstrap.ps1

cluster-down:
	powershell -ExecutionPolicy Bypass -File infrastructure/scripts/teardown.ps1

# ─────────────────────────────────────────────────────────────────────────────
#  Kubernetes — data layer (Bitnami Helm)
# ─────────────────────────────────────────────────────────────────────────────
k8s-data:
	kubectl create namespace $(DATA_NS) --dry-run=client -o yaml | kubectl apply -f -
	kubectl apply -f infrastructure/k8s/postgres-dev.yml
	kubectl apply -f infrastructure/k8s/kafka-dev.yml
	kubectl rollout status statefulset/postgres-kyc        -n $(DATA_NS) --timeout=120s
	kubectl rollout status statefulset/postgres-monitoring -n $(DATA_NS) --timeout=120s
	kubectl rollout status statefulset/postgres-cases      -n $(DATA_NS) --timeout=120s
	kubectl rollout status statefulset/kafka               -n $(DATA_NS) --timeout=180s

k8s-topics:
	kubectl exec -n $(DATA_NS) kafka-0 -- bash -c "\
		/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic aiops.telemetry.metrics --partitions 6 && \
		/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic aiops.telemetry.traces  --partitions 6 && \
		/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic aiops.telemetry.logs   --partitions 6 && \
		/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic aiops.features    --partitions 3 && \
		/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic aiops.incidents   --partitions 3 && \
		/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic aiops.decisions   --partitions 3 && \
		/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic aiops.actions     --partitions 3 && \
		/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic aiops.outcomes    --partitions 3 && \
		/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic aiops.alerts            --partitions 3 && \
		/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic aml.llm.analysis        --partitions 3 && \
		/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic aiops.service.heartbeat --partitions 3 \
		"

k8s-ollama-pull:
	@echo "Triggering Ollama model pull inside running pod..."
	kubectl exec -n $(AIOPS_NS) deployment/ollama -- ollama pull qwen2.5:3b

k8s-monitoring:
	kubectl create namespace $(MONITORING_NS) --dry-run=client -o yaml | kubectl apply -f -
	$(HELM) repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update
	$(HELM) repo add grafana https://grafana.github.io/helm-charts --force-update
	$(HELM) upgrade --install kube-prom-stack prometheus-community/kube-prometheus-stack \
		-n $(MONITORING_NS) --wait
	$(HELM) upgrade --install loki grafana/loki-stack \
		-n $(MONITORING_NS) --set grafana.enabled=false --wait
	$(HELM) upgrade --install tempo grafana/tempo \
		-n $(MONITORING_NS) --wait

# ─────────────────────────────────────────────────────────────────────────────
#  Kubernetes — application deployments
# ─────────────────────────────────────────────────────────────────────────────
k8s-aml:
	kubectl apply -f infrastructure/k8s/aml-platform.yml

k8s-aiops:
	kubectl apply -f infrastructure/k8s/aiops.yml

k8s-dashboard:
	kubectl apply -f infrastructure/k8s/dashboard.yml

k8s-agent:
	kubectl apply -f aiops/agent-service/k8s/rbac.yaml
	kubectl apply -f aiops/agent-service/k8s/deployment.yaml
	kubectl apply -f aiops/agent-service/k8s/service.yaml

k8s-full: k8s-data \
          image-aml push-aml k8s-aml \
          image-aiops push-aiops k8s-aiops \
          image-dashboard push-dashboard k8s-dashboard \
          k8s-topics

k8s-status:
	@echo.
	@echo --- Nodes ---
	kubectl get nodes
	@echo.
	@echo --- aml namespace ---
	kubectl get pods,svc,hpa -n $(AML_NS)
	@echo.
	@echo --- aiops namespace ---
	kubectl get pods,svc -n $(AIOPS_NS)
	@echo.
	@echo --- data namespace ---
	kubectl get pods,svc -n $(DATA_NS)
	@echo.
	@echo --- monitoring namespace ---
	kubectl get pods,svc -n $(MONITORING_NS)

k8s-delete:
	kubectl delete namespace $(AML_NS)    --ignore-not-found
	kubectl delete namespace $(AIOPS_NS)  --ignore-not-found
	kubectl delete namespace $(DATA_NS)   --ignore-not-found

# ─────────────────────────────────────────────────────────────────────────────
#  Port-forwards (each opens in background; kill with Ctrl-C)
# ─────────────────────────────────────────────────────────────────────────────
pf-aml:
	kubectl port-forward -n $(AML_NS) svc/case-management        8080:8080 &
	kubectl port-forward -n $(AML_NS) svc/transaction-monitoring 8081:8081 &
	kubectl port-forward -n $(AML_NS) svc/customer-kyc           8082:8082 &
	@echo "AML services forwarded on 8080, 8081, 8082 (background). kill %1 %2 %3 to stop."

pf-aiops:
	kubectl port-forward -n $(AIOPS_NS) svc/telemetry-collector  9001:9001 &
	kubectl port-forward -n $(AIOPS_NS) svc/stream-processor     9002:9002 &
	kubectl port-forward -n $(AIOPS_NS) svc/ml-engine            8000:8000 &
	kubectl port-forward -n $(AIOPS_NS) svc/llm-engine           8001:8001 &
	kubectl port-forward -n $(AIOPS_NS) svc/ollama               11434:11434 &
	kubectl port-forward -n $(AIOPS_NS) svc/decision-engine      9003:9003 &
	kubectl port-forward -n $(AIOPS_NS) svc/remediation-engine   9004:9004 &
	kubectl port-forward -n $(AIOPS_NS) svc/alerting-service     9005:9005 &
	kubectl port-forward -n $(AIOPS_NS) svc/feedback-service     9006:9006 &
	@echo "AIOps services forwarded on 9001-9006, 8000, 8001, 11434 (background)."

pf-agent:
	kubectl port-forward -n $(AIOPS_NS) svc/agent-service 9007:9007
	@echo "AMLOps Agent: http://localhost:9007"

pf-dashboard:
	kubectl port-forward -n $(AIOPS_NS) svc/aiops-dashboard 3001:80
	@echo "Dashboard: http://localhost:3001"

pf-obs:
	kubectl port-forward -n $(MONITORING_NS) svc/kube-prom-stack-kube-prome-prometheus  9090:9090 &
	kubectl port-forward -n $(MONITORING_NS) svc/kube-prom-stack-grafana               3000:80   &
	kubectl port-forward -n $(MONITORING_NS) svc/loki                                  3100:3100 &
	kubectl port-forward -n $(MONITORING_NS) svc/tempo                                 3200:3200 &
	@echo "Grafana: http://localhost:3000  Prometheus: http://localhost:9090  Loki: http://localhost:3100  Tempo: http://localhost:3200"

# ─────────────────────────────────────────────────────────────────────────────
#  Legacy Helm deploy (kept for reference)
# ─────────────────────────────────────────────────────────────────────────────
deploy:
	helm upgrade --install case-management infrastructure/helm/case-management \
		-n $(AML_NS) --create-namespace
	helm upgrade --install transaction-monitoring infrastructure/helm/transaction-monitoring \
		-n $(AML_NS)
	helm upgrade --install customer-kyc infrastructure/helm/customer-kyc \
		-n $(AML_NS)

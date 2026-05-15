import json
import os
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

# ── Config ─────────────────────────────────────────────────────────────────
OLLAMA_URL     = os.getenv("OLLAMA_URL",     "http://ollama:11434")
OLLAMA_MODEL   = os.getenv("OLLAMA_MODEL",   "qwen2.5:3b")
PROMETHEUS_URL = os.getenv("PROMETHEUS_URL",
    "http://prometheus-operated.monitoring.svc.cluster.local:9090")
LOKI_URL       = os.getenv("LOKI_URL",
    "http://loki.monitoring.svc.cluster.local:3100")
ALERTING_URL   = os.getenv("ALERTING_URL",
    "http://alerting-service.aiops.svc.cluster.local:9005")

# ── Tool schemas (OpenAI / Ollama format) ───────────────────────────────────
TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "kubectl",
            "description": (
                "Run a kubectl command against the Kubernetes cluster. "
                "Namespaces: aml (domain services), aiops (intelligence layer), "
                "monitoring (Prometheus/Loki/Tempo). "
                "Examples: 'get pods -n aml', 'describe pod <name> -n aiops', "
                "'logs <pod> -n aiops --tail=50', "
                "'rollout restart deployment/<name> -n aiops'"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "args": {
                        "type": "string",
                        "description": "Arguments after 'kubectl', e.g. 'get pods -n aml -o wide'"
                    }
                },
                "required": ["args"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "prometheus_query",
            "description": (
                "Execute a PromQL instant query. Use to inspect CPU, memory, "
                "request rate, error rate, or p95 latency for any service. "
                "Job label names match service names: customer-kyc, "
                "transaction-monitoring, case-management, ml-engine, llm-engine, etc."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "PromQL expression"}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_health_scores",
            "description": (
                "Fetch current per-service health scores (0–1) from the alerting-service. "
                "Combines anomaly incident frequency with live CPU utilisation. "
                "Call this first when asked about overall system health."
            ),
            "parameters": {"type": "object", "properties": {}, "required": []}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "loki_query",
            "description": (
                "Query Loki for recent log lines. "
                "Example: '{namespace=\"aml\"} |= \"ERROR\"' or "
                "'{app=\"transaction-monitoring\"} | json | level=\"error\"'"
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "logql": {
                        "type": "string",
                        "description": "LogQL stream selector + filter"
                    },
                    "minutes": {
                        "type": "integer",
                        "description": "Lookback window in minutes (default 15)"
                    }
                },
                "required": ["logql"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "run_command",
            "description": (
                "Run a shell command. Use for tasks not covered by kubectl, "
                "such as checking files, testing connectivity, or inspecting configs."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "command": {"type": "string", "description": "Shell command to run"}
                },
                "required": ["command"]
            }
        }
    }
]

# ── System prompt ───────────────────────────────────────────────────────────
SYSTEM_PROMPT = """\
You are the AMLOps Agent — an intelligent operator for an 11-service Kubernetes \
anti-money laundering (AML) observability platform.

Platform layout:
- aml namespace: customer-kyc (8082), transaction-monitoring (8081), case-management (8080)
- aiops namespace: telemetry-collector → stream-processor → ml-engine → llm-engine \
→ decision-engine → remediation-engine → alerting-service → feedback-service → agent-service
- monitoring namespace: Prometheus, Loki, Grafana Tempo
- Messaging: Apache Kafka (Strimzi), topics: aml.{customers,transactions,cases}.events, \
aiops.telemetry.{metrics,logs,traces}, aiops.{features,incidents,decisions,outcomes}, \
aiops.service.heartbeat, aml.llm.analysis

Key algorithms:
- Anomaly: Isolation Forest, score = max(0, min(1, 0.5 - d(phi)))
- RCA: Pearson correlation, sliding window W=100, top-10 by |r|
- Breach: OLS on last 10 p95 values, ETA = (500ms - b0) / b1
- LLM narration: Qwen2.5-3B via Ollama with LoRA adapters (same model you are)
- Decision: confidence x blast-radius x ETA -> SCALE_OUT / RESTART_POD / ESCALATE
- AML risk: R = 100*(1 - product(1 - cr/100)) across rules AML-101/202/303/404

Rules:
1. Check current state first (health scores or prometheus) before acting.
2. Prefer targeted fixes (restart one pod) over broad ones.
3. Warn before destructive operations (delete, scale-to-zero).
4. Show metric values and log snippets to justify your diagnosis.
5. Be concise — this is a technical ops context.\
"""

# ── Tool implementations ────────────────────────────────────────────────────

def _truncate(s: str, n: int = 3000) -> str:
    return s[:n] + "\n...(truncated)" if len(s) > n else s


async def tool_kubectl(args: str) -> str:
    try:
        result = subprocess.run(
            ["kubectl"] + args.split(),
            capture_output=True, text=True, timeout=30
        )
        return _truncate((result.stdout + result.stderr).strip() or "(no output)")
    except FileNotFoundError:
        return "kubectl not found — ensure the pod has kubectl installed and RBAC is configured"
    except subprocess.TimeoutExpired:
        return "kubectl timed out (30 s)"


async def tool_prometheus(query: str) -> str:
    async with httpx.AsyncClient(timeout=10) as h:
        try:
            r = await h.get(
                f"{PROMETHEUS_URL}/api/v1/query",
                params={"query": query}
            )
            d = r.json()
            if d.get("status") != "success":
                return f"Prometheus error: {d}"
            results = d["data"]["result"]
            if not results:
                return "No data returned"
            lines = []
            for item in results[:25]:
                metric = item.get("metric", {})
                value  = item.get("value", [None, "?"])[1]
                label  = ", ".join(
                    f'{k}="{v}"' for k, v in metric.items() if k != "__name__"
                )
                lines.append(f"{label or query}: {value}")
            return "\n".join(lines)
        except Exception as e:
            return f"Prometheus unreachable: {e}"


async def tool_health_scores() -> str:
    async with httpx.AsyncClient(timeout=10) as h:
        try:
            r = await h.get(f"{ALERTING_URL}/api/health")
            return json.dumps(r.json(), indent=2)
        except Exception as e:
            return f"alerting-service unreachable: {e}"


async def tool_loki(logql: str, minutes: int = 15) -> str:
    now   = datetime.now(timezone.utc)
    start = (now - timedelta(minutes=minutes)).isoformat()
    async with httpx.AsyncClient(timeout=15) as h:
        try:
            r = await h.get(
                f"{LOKI_URL}/loki/api/v1/query_range",
                params={
                    "query": logql,
                    "start": start,
                    "end":   now.isoformat(),
                    "limit": 50
                }
            )
            data    = r.json()
            streams = data.get("data", {}).get("result", [])
            if not streams:
                return "No log entries found"
            lines = []
            for stream in streams[:5]:
                for ts, msg in stream.get("values", [])[:15]:
                    t = datetime.fromtimestamp(
                        int(ts) / 1e9, tz=timezone.utc
                    ).strftime("%H:%M:%S")
                    lines.append(f"[{t}] {msg[:200]}")
            return _truncate("\n".join(lines))
        except Exception as e:
            return f"Loki unreachable: {e}"


async def tool_run_command(command: str) -> str:
    try:
        result = subprocess.run(
            command, shell=True, capture_output=True, text=True, timeout=30
        )
        return _truncate((result.stdout + result.stderr).strip() or "(no output)")
    except subprocess.TimeoutExpired:
        return "Command timed out (30 s)"
    except Exception as e:
        return f"Error: {e}"


async def dispatch(name: str, arguments) -> str:
    # arguments may arrive as dict or JSON string depending on Ollama version
    if isinstance(arguments, str):
        try:
            arguments = json.loads(arguments)
        except json.JSONDecodeError:
            arguments = {}

    if name == "kubectl":
        return await tool_kubectl(arguments.get("args", ""))
    if name == "prometheus_query":
        return await tool_prometheus(arguments.get("query", ""))
    if name == "get_health_scores":
        return await tool_health_scores()
    if name == "loki_query":
        return await tool_loki(
            arguments.get("logql", ""),
            arguments.get("minutes", 15)
        )
    if name == "run_command":
        return await tool_run_command(arguments.get("command", ""))
    return f"Unknown tool: {name}"


# ── Ollama chat call ────────────────────────────────────────────────────────

async def ollama_chat(messages: list) -> dict:
    async with httpx.AsyncClient(timeout=120) as h:
        r = await h.post(
            f"{OLLAMA_URL}/api/chat",
            json={
                "model":    OLLAMA_MODEL,
                "messages": messages,
                "tools":    TOOLS,
                "stream":   False
            }
        )
        r.raise_for_status()
        return r.json()


# ── FastAPI ─────────────────────────────────────────────────────────────────
app = FastAPI(title="AMLOps Agent", version="1.0.0", docs_url=None, redoc_url=None)

STATIC = Path(__file__).parent / "static"


@app.get("/")
async def index():
    return FileResponse(STATIC / "index.html")


@app.websocket("/ws")
async def chat(ws: WebSocket):
    await ws.accept()

    # Seed conversation with system prompt
    history: list[dict] = [{"role": "system", "content": SYSTEM_PROMPT}]

    async def send(payload: dict):
        await ws.send_text(json.dumps(payload))

    try:
        while True:
            raw  = await ws.receive_text()
            data = json.loads(raw)
            if data.get("type") != "message":
                continue

            history.append({"role": "user", "content": data["content"]})

            # ── Agentic loop ──────────────────────────────────────────────
            for _ in range(10):           # max 10 tool-call rounds
                try:
                    resp = await ollama_chat(history)
                except Exception as e:
                    await send({"type": "error", "content": f"Ollama error: {e}"})
                    break

                msg        = resp.get("message", {})
                tool_calls = msg.get("tool_calls") or []

                if tool_calls:
                    # Let the UI know the model is using tools
                    history.append({
                        "role":       "assistant",
                        "content":    msg.get("content", ""),
                        "tool_calls": tool_calls
                    })

                    for tc in tool_calls:
                        fn   = tc.get("function", {})
                        name = fn.get("name", "")
                        args = fn.get("arguments", {})

                        await send({
                            "type":  "tool_call",
                            "tool":  name,
                            "input": args if isinstance(args, dict) else {}
                        })

                        result = await dispatch(name, args)

                        await send({
                            "type":   "tool_result",
                            "tool":   name,
                            "output": result
                        })

                        history.append({
                            "role":    "tool",
                            "content": result
                        })
                else:
                    # Final text response
                    text = msg.get("content", "")
                    await send({"type": "assistant", "content": text})
                    history.append({"role": "assistant", "content": text})
                    break

    except WebSocketDisconnect:
        pass


if STATIC.exists():
    app.mount("/static", StaticFiles(directory=STATIC), name="static")

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=9007)

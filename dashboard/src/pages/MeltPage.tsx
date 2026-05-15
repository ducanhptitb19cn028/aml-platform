import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer,
} from 'recharts'
import { format } from 'date-fns'
import { TrendingUp, Zap, ScrollText, GitBranch, ExternalLink } from 'lucide-react'
import { api } from '../api/client'
import type { Incident, RemediationRecord } from '../types'

// ── Observability helpers ─────────────────────────────────────────────────────

function nowSec()          { return Math.floor(Date.now() / 1000) }
function agoSec(m: number) { return nowSec() - m * 60 }

const AIOPS_SERVICES = new Set([
  'telemetry-collector', 'stream-processor', 'decision-engine',
  'remediation-engine',  'alerting-service',  'feedback-service',
  'ml-engine',           'llm-engine',
])

interface PromSeries { metric: Record<string, string>; values: [number, string][] }
interface LokiStream  { stream: Record<string, string>; values: [string, string][] }
interface TempoTrace  {
  traceID: string; rootServiceName: string; rootTraceName: string
  startTimeUnixNano: string; durationMs: number | null
}

type NsFilter = 'all' | 'aml' | 'aiops'

function nsRegex(ns: NsFilter) {
  if (ns === 'aml')   return 'aml'
  if (ns === 'aiops') return 'aiops'
  return 'aml|aiops'
}

const JOB_REGEX: Record<NsFilter, string> = {
  aml:   'customer-kyc|transaction-monitoring|case-management',
  aiops: 'telemetry-collector|stream-processor|decision-engine|remediation-engine|alerting-service|feedback-service',
  all:   'customer-kyc|transaction-monitoring|case-management|telemetry-collector|stream-processor|decision-engine|remediation-engine|alerting-service|feedback-service',
}

async function promRange(query: string): Promise<PromSeries[]> {
  const p = new URLSearchParams({ query, start: String(agoSec(10)), end: String(nowSec()), step: '30' })
  const res = await fetch(`/prom/api/v1/query_range?${p}`)
  if (!res.ok) throw new Error(res.statusText)
  return ((await res.json()).data.result) as PromSeries[]
}

async function lokiLogs(ns: NsFilter, limit = 30): Promise<LokiStream[]> {
  const p = new URLSearchParams({
    query:     `{namespace=~"${nsRegex(ns)}"}`,
    limit:     String(limit),
    start:     String(agoSec(10) * 1_000_000_000),
    end:       String(nowSec()   * 1_000_000_000),
    direction: 'backward',
  })
  const res = await fetch(`/lokiapi/loki/api/v1/query_range?${p}`)
  if (!res.ok) throw new Error(res.statusText)
  return ((await res.json()).data.result) as LokiStream[]
}

async function tempoSearch(limit = 10): Promise<TempoTrace[]> {
  const p = new URLSearchParams({ limit: String(limit), start: String(agoSec(10)), end: String(nowSec()) })
  const res = await fetch(`/tempo/api/search?${p}`)
  if (!res.ok) throw new Error(res.statusText)
  return ((await res.json()).traces ?? []) as TempoTrace[]
}

// ── Shared UI ─────────────────────────────────────────────────────────────────

const SERIES_COLORS = ['#60a5fa', '#f59e0b', '#34d399', '#a78bfa', '#fb923c', '#f87171', '#4ade80']

function Panel({ letter, title, icon: Icon, color, children }: {
  letter: string; title: string
  icon: React.ComponentType<{ className?: string }>
  color: string; children: React.ReactNode
}) {
  return (
    <div className="bg-surface-800 rounded-lg border border-surface-700 overflow-hidden flex flex-col">
      <div className={`flex items-center gap-2 px-4 py-2.5 border-b border-surface-700 ${color}`}>
        <span className="text-base font-bold font-mono">{letter}</span>
        <Icon className="w-3.5 h-3.5" />
        <span className="text-sm font-semibold">{title}</span>
      </div>
      <div className="p-4 flex-1 overflow-auto">{children}</div>
    </div>
  )
}

function Empty({ msg }: { msg: string }) {
  return <p className="text-slate-500 text-sm py-6 text-center">{msg}</p>
}

function NsBadge({ ns }: { ns: 'aml' | 'aiops' | string }) {
  const isAiops = ns === 'aiops' || AIOPS_SERVICES.has(ns)
  return (
    <span className={`text-[10px] px-1 py-0.5 rounded font-mono flex-shrink-0 ${
      isAiops ? 'bg-purple-900/60 text-purple-300' : 'bg-blue-900/60 text-blue-300'
    }`}>
      {isAiops ? 'aiops' : 'aml'}
    </span>
  )
}

// ── M — Metrics ───────────────────────────────────────────────────────────────

function buildChart(series: PromSeries[]) {
  const timeMap = new Map<number, Record<string, number>>()
  for (const s of series) {
    const label = s.metric.pod ?? s.metric.container ?? s.metric.job ?? 'value'
    for (const [ts, val] of s.values) {
      const pt = timeMap.get(ts) ?? {}
      pt[label] = parseFloat(parseFloat(val).toFixed(4))
      timeMap.set(ts, pt)
    }
  }
  return Array.from(timeMap.entries())
    .sort(([a], [b]) => a - b)
    .map(([ts, vals]) => ({ time: format(new Date(ts * 1000), 'HH:mm'), ...vals }))
}

function MiniChart({ data, keys, unit = '' }: { data: object[]; keys: string[]; unit?: string }) {
  if (data.length === 0) return <Empty msg="No data" />
  return (
    <ResponsiveContainer width="100%" height={110}>
      <AreaChart data={data} margin={{ top: 2, right: 4, left: -24, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
        <XAxis dataKey="time" tick={{ fontSize: 10, fill: '#94a3b8' }} />
        <YAxis tick={{ fontSize: 10, fill: '#94a3b8' }} unit={unit} />
        <Tooltip contentStyle={{ background: '#1e293b', border: '1px solid #334155', fontSize: 11 }}
          labelStyle={{ color: '#94a3b8' }} />
        {keys.map((k, i) => (
          <Area key={k} dataKey={k} stroke={SERIES_COLORS[i % SERIES_COLORS.length]}
            fill={SERIES_COLORS[i % SERIES_COLORS.length] + '22'} strokeWidth={1.5} dot={false} />
        ))}
      </AreaChart>
    </ResponsiveContainer>
  )
}

function MetricsSection({ ns }: { ns: NsFilter }) {
  const nsQ = nsRegex(ns)

  const cpuQ = useQuery({
    queryKey:        ['melt-cpu', ns],
    queryFn:         () => promRange(
      `topk(8, sum by (pod) (rate(container_cpu_usage_seconds_total{namespace=~"${nsQ}",container!="",container!="POD"}[1m])))`
    ),
    refetchInterval: 30_000,
  })
  const memQ = useQuery({
    queryKey:        ['melt-mem', ns],
    queryFn:         () => promRange(
      `topk(8, sum by (pod) (container_memory_working_set_bytes{namespace=~"${nsQ}",container!="",container!="POD"}) / 1048576)`
    ),
    refetchInterval: 30_000,
  })
  const jvmCpuQ = useQuery({
    queryKey:        ['melt-jvm-cpu', ns],
    queryFn:         () => promRange(`process_cpu_usage{job=~"${JOB_REGEX[ns]}"}`),
    refetchInterval: 30_000,
  })
  const pipelineQ = useQuery({
    queryKey:        ['melt-pipeline', ns],
    queryFn:         () => promRange(
      `sum by (job) (rate(http_server_requests_seconds_count{namespace=~"${nsQ}"}[1m]))`
    ),
    refetchInterval: 30_000,
    enabled:         ns !== 'aml',
  })

  if (cpuQ.isError) return <Empty msg="Prometheus unreachable — run: make pf-obs" />

  const cpuData      = buildChart(cpuQ.data ?? [])
  const memData      = buildChart(memQ.data ?? [])
  const jvmData      = buildChart(jvmCpuQ.data ?? [])
  const pipelineData = buildChart(pipelineQ.data ?? [])
  const podNames     = [...new Set((cpuQ.data ?? []).map(s => s.metric.pod ?? 'value'))]
  const jvmNames     = [...new Set((jvmCpuQ.data ?? []).map(s => s.metric.job ?? 'value'))]
  const svcNames     = [...new Set((pipelineQ.data ?? []).map(s => s.metric.job ?? 'value'))]

  return (
    <div className="space-y-5">
      <div>
        <p className="text-xs text-slate-400 mb-1.5">Container CPU (cores) · top 8 pods · 10 min</p>
        {cpuQ.isLoading ? <Empty msg="Loading…" /> : <MiniChart data={cpuData} keys={podNames} />}
      </div>
      <div>
        <p className="text-xs text-slate-400 mb-1.5">Container memory (MiB) · top 8 pods · 10 min</p>
        {memQ.isLoading ? <Empty msg="Loading…" /> : <MiniChart data={memData} keys={podNames} />}
      </div>
      <div>
        <p className="text-xs text-slate-400 mb-1.5">JVM CPU usage (0–1) · Spring Boot services · 10 min</p>
        {jvmCpuQ.isLoading ? <Empty msg="Loading…" /> : <MiniChart data={jvmData} keys={jvmNames} unit="%" />}
      </div>
      {ns !== 'aml' && (
        <div>
          <p className="text-xs text-slate-400 mb-1.5">HTTP req/s · 10 min</p>
          {pipelineQ.isLoading
            ? <Empty msg="Loading…" />
            : <MiniChart data={pipelineData} keys={svcNames} />}
        </div>
      )}
    </div>
  )
}

// ── E — Events ────────────────────────────────────────────────────────────────

function EventsSection({
  incidents, remediations, ns,
}: { incidents: Incident[]; remediations: RemediationRecord[]; ns: NsFilter }) {
  const { data: storedInc = [] } = useQuery({ queryKey: ['incidents'],    queryFn: api.incidents,    refetchInterval: 10_000 })
  const { data: storedRem = [] } = useQuery({ queryKey: ['remediations'], queryFn: api.remediations, refetchInterval: 10_000 })

  const allInc = [
    ...incidents,
    ...storedInc.filter(i => !incidents.find(l => l.incidentId === i.incidentId)),
  ].slice(0, 50)
  const allRem = [
    ...remediations,
    ...storedRem.filter(r => !remediations.find(l => l.recordId === r.recordId)),
  ].slice(0, 30)

  type Ev = { ts: number; kind: 'INC' | 'REM'; svc: string; label: string; dot: string; text: string }

  const events: Ev[] = [
    ...allInc.map(i => {
      const svc = i.affectedServices?.[0] ?? '?'
      return {
        ts:    new Date(i.detectedAt).getTime(),
        kind:  'INC' as const,
        svc,
        label: `score ${i.anomalyScore.toFixed(2)}`,
        dot:   i.anomalyScore >= 0.9 ? 'bg-red-400' : i.anomalyScore >= 0.5 ? 'bg-yellow-400' : 'bg-blue-400',
        text:  i.anomalyScore >= 0.9 ? 'text-red-400' : i.anomalyScore >= 0.5 ? 'text-yellow-400' : 'text-blue-400',
      }
    }),
    ...allRem.map(r => ({
      ts:    new Date(r.executedAt).getTime(),
      kind:  'REM' as const,
      svc:   r.targetService,
      label: r.action,
      dot:   r.status === 'COMPLETED' ? 'bg-green-400' : r.status === 'FAILED' ? 'bg-red-400' : 'bg-slate-500',
      text:  r.status === 'COMPLETED' ? 'text-green-400' : r.status === 'FAILED' ? 'text-red-400' : 'text-slate-400',
    })),
  ]
    .filter(e => {
      if (ns === 'aml')   return !AIOPS_SERVICES.has(e.svc)
      if (ns === 'aiops') return AIOPS_SERVICES.has(e.svc)
      return true
    })
    .sort((a, b) => b.ts - a.ts)
    .slice(0, 20)

  if (events.length === 0) return <Empty msg="No events yet — waiting for SSE or API data…" />

  return (
    <ol className="space-y-2">
      {events.map((e, i) => (
        <li key={i} className="flex items-start gap-2 text-xs">
          <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 mt-1.5 ${e.dot}`} />
          <div className="min-w-0 flex items-baseline gap-1.5 flex-wrap">
            <span className="text-slate-500 tabular-nums">{format(new Date(e.ts), 'HH:mm:ss')}</span>
            <span className={`font-semibold ${e.text}`}>{e.kind}</span>
            <NsBadge ns={e.svc} />
            <span className="text-slate-400 truncate">{e.svc}</span>
            <span className="text-slate-300 truncate">{e.label}</span>
          </div>
        </li>
      ))}
    </ol>
  )
}

// ── L — Logs ──────────────────────────────────────────────────────────────────

const LEVEL_COLOR: Record<string, string> = {
  ERROR: 'text-red-400', WARN: 'text-yellow-400', INFO: 'text-blue-400', DEBUG: 'text-slate-500',
}

const PLAIN_LEVEL_RE = /\b(ERROR|WARN(?:ING)?|INFO|DEBUG)\b/i

function extractLevel(raw: string): string {
  try {
    const p = JSON.parse(raw)
    const l = String(p.level ?? p.levelname ?? p.severity ?? '').toUpperCase()
    if (l) return l.startsWith('WARN') ? 'WARN' : l
  } catch { /* not JSON */ }
  const m = PLAIN_LEVEL_RE.exec(raw)
  if (m) return m[1].toUpperCase().startsWith('WARN') ? 'WARN' : m[1].toUpperCase()
  return ''
}

function LogsSection({ ns }: { ns: NsFilter }) {
  const { data, isError, isLoading } = useQuery({
    queryKey:        ['melt-logs', ns],
    queryFn:         () => lokiLogs(ns),
    refetchInterval: 15_000,
  })

  if (isError)   return <Empty msg="Loki unreachable — run: make pf-obs" />
  if (isLoading) return <Empty msg="Loading…" />

  const lines = (data ?? [])
    .flatMap(s =>
      s.values.map(([nsTs, raw]) => {
        let msg = raw, svc = ''
        try {
          const p = JSON.parse(raw)
          msg = p.message ?? p.msg ?? p.text ?? raw
          svc = p.service ?? p['service.name'] ?? ''
        } catch { /* plain text */ }
        return {
          ts:        Number(BigInt(nsTs) / 1_000_000n),
          level:     extractLevel(raw),
          namespace: s.stream['namespace'] ?? '',
          service:   svc || (s.stream['app'] ?? s.stream['app_kubernetes_io_name'] ?? s.stream['pod'] ?? '?'),
          msg,
        }
      })
    )
    .sort((a, b) => b.ts - a.ts)
    .slice(0, 25)

  if (lines.length === 0) return <Empty msg="No logs in last 5 min" />

  return (
    <div className="space-y-1.5 font-mono">
      {lines.map((l, i) => (
        <div key={i} className="flex items-start gap-1.5 text-xs leading-relaxed min-w-0">
          <span className="text-slate-500 tabular-nums flex-shrink-0">{format(new Date(l.ts), 'HH:mm:ss')}</span>
          {l.level && (
            <span className={`flex-shrink-0 w-9 ${LEVEL_COLOR[l.level] ?? 'text-slate-400'}`}>
              {l.level.slice(0, 4)}
            </span>
          )}
          <NsBadge ns={l.namespace || l.service} />
          <span className="text-slate-500 flex-shrink-0 hidden sm:block w-28 truncate">[{l.service}]</span>
          <span className="text-slate-300 truncate">{l.msg}</span>
        </div>
      ))}
    </div>
  )
}

// ── T — Traces ────────────────────────────────────────────────────────────────

const GRAFANA_EXPLORE = 'http://localhost:3000/explore'

function TracesSection({ ns }: { ns: NsFilter }) {
  const { data, isError, isLoading } = useQuery({
    queryKey:        ['melt-traces'],
    queryFn:         () => tempoSearch(),
    refetchInterval: 30_000,
  })

  const grafanaLink = `${GRAFANA_EXPLORE}?orgId=1&left=${encodeURIComponent(
    JSON.stringify({ datasource: 'tempo', queries: [{ refId: 'A', queryType: 'nativeSearch' }], range: { from: 'now-10m', to: 'now' } })
  )}`

  const footer = (
    <div className="flex gap-3 text-xs pt-2 border-t border-surface-700 mt-3">
      <a href={grafanaLink} target="_blank" rel="noreferrer"
        className="flex items-center gap-1 text-blue-400 hover:text-blue-300">
        <ExternalLink className="w-3 h-3" /> Open in Grafana Explore
      </a>
    </div>
  )

  if (isError)   return <div><Empty msg="Tempo unreachable — run: make pf-obs" />{footer}</div>
  if (isLoading) return <Empty msg="Loading…" />

  const traces = (data ?? []).filter(t => {
    if (ns === 'aml')   return !AIOPS_SERVICES.has(t.rootServiceName)
    if (ns === 'aiops') return AIOPS_SERVICES.has(t.rootServiceName)
    return true
  })

  const emptyMsg = ns === 'aiops'
    ? 'No AIOps traces — OTEL auto-instrumentation not yet configured for AIOps services'
    : 'No traces in last 10 min — verify OTEL exporter → tempo:4317'

  return (
    <div>
      {traces.length === 0
        ? <Empty msg={emptyMsg} />
        : (
          <table className="w-full text-xs">
            <thead>
              <tr className="text-slate-500 border-b border-surface-700">
                <th className="text-left pb-1.5 font-medium w-6" />
                <th className="text-left pb-1.5 font-medium">Service</th>
                <th className="text-left pb-1.5 font-medium px-2">Operation</th>
                <th className="text-right pb-1.5 font-medium">ms</th>
                <th className="text-right pb-1.5 font-medium pl-2">Time</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-surface-700">
              {traces.map(t => {
                const startMs = Number(BigInt(t.startTimeUnixNano) / 1_000_000n)
                const dur = t.durationMs ?? null
                return (
                  <tr key={t.traceID} className="hover:bg-surface-700/40">
                    <td className="py-1.5"><NsBadge ns={t.rootServiceName} /></td>
                    <td className="py-1.5 text-blue-400 truncate max-w-[100px]">{t.rootServiceName}</td>
                    <td className="py-1.5 text-slate-300 truncate max-w-[140px] px-2">{t.rootTraceName}</td>
                    <td className={`py-1.5 text-right tabular-nums ${dur != null && dur > 500 ? 'text-red-400' : 'text-slate-400'}`}>
                      {dur ?? '—'}
                    </td>
                    <td className="py-1.5 text-right text-slate-500 tabular-nums pl-2">
                      {format(new Date(startMs), 'HH:mm:ss')}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )
      }
      {footer}
    </div>
  )
}

// ── Namespace selector ────────────────────────────────────────────────────────

function NsToggle({ value, onChange }: { value: NsFilter; onChange: (v: NsFilter) => void }) {
  const btn = (v: NsFilter, label: string) => (
    <button
      onClick={() => onChange(v)}
      className={`px-3 py-1 text-xs rounded font-medium transition-colors ${
        value === v
          ? 'bg-slate-600 text-white'
          : 'text-slate-400 hover:text-slate-200'
      }`}
    >
      {label}
    </button>
  )
  return (
    <div className="flex items-center gap-1 bg-surface-800 border border-surface-700 rounded px-1 py-0.5">
      {btn('all',   'All')}
      {btn('aml',   'AML')}
      {btn('aiops', 'AIOps')}
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

interface Props {
  liveIncidents:    Incident[]
  liveRemediations: RemediationRecord[]
}

export default function MeltPage({ liveIncidents, liveRemediations }: Props) {
  const [ns, setNs] = useState<NsFilter>('all')

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-300">
          MELT signals —{' '}
          <span className="text-slate-500 font-normal">Metrics · Events · Logs · Traces</span>
        </h2>
        <NsToggle value={ns} onChange={setNs} />
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
        <Panel letter="M" title="Metrics" icon={TrendingUp} color="text-blue-300">
          <MetricsSection ns={ns} />
        </Panel>
        <Panel letter="E" title="Events" icon={Zap} color="text-yellow-300">
          <EventsSection incidents={liveIncidents} remediations={liveRemediations} ns={ns} />
        </Panel>
        <Panel letter="L" title="Logs" icon={ScrollText} color="text-green-300">
          <LogsSection ns={ns} />
        </Panel>
        <Panel letter="T" title="Traces" icon={GitBranch} color="text-purple-300">
          <TracesSection ns={ns} />
        </Panel>
      </div>
    </div>
  )
}

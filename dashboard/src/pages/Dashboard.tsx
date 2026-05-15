import { useCallback, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { RefreshCw, ShieldAlert, Activity, Brain } from 'lucide-react'
import { format } from 'date-fns'
import clsx from 'clsx'
import { api } from '../api/client'
import type { Incident, RemediationRecord } from '../types'
import ServiceHealthCard from '../components/ServiceHealthCard'
import IncidentTable from '../components/IncidentTable'
import RemediationLog from '../components/RemediationLog'
import OutcomeChart from '../components/OutcomeChart'
import LiveAnomalyChart from '../components/LiveAnomalyChart'
import LlmAnalysisPanel from '../components/LlmAnalysisPanel'
import { useEventStream } from '../hooks/useEventStream'

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-surface-800 rounded-lg p-4 border border-surface-700">
      <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-wide mb-3">
        {title}
      </h2>
      {children}
    </div>
  )
}

export default function Dashboard() {
  const queryClient = useQueryClient()

  const { data: health = [],       dataUpdatedAt: h_at } = useQuery({ queryKey: ['health'],       queryFn: api.serviceHealth,  refetchInterval: 5_000 })
  const { data: incidents = [],    dataUpdatedAt: i_at } = useQuery({ queryKey: ['incidents'],    queryFn: api.incidents,      refetchInterval: 5_000 })
  const { data: remediations = [], dataUpdatedAt: r_at } = useQuery({ queryKey: ['remediations'], queryFn: api.remediations,   refetchInterval: 5_000 })
  const { data: summary = { RESOLVED: 0, NO_EFFECT: 0, DEGRADED_FURTHER: 0 }, dataUpdatedAt: s_at } =
    useQuery({ queryKey: ['summary'], queryFn: api.outcomeSummary, refetchInterval: 10_000 })

  // SSE live push — merged with polling data
  const [liveIncidents, setLiveIncidents] = useState<Incident[]>([])
  const [liveRemediations, setLiveRemediations] = useState<RemediationRecord[]>([])

  const handleIncident = useCallback((data: unknown) => {
    const inc = data as Incident
    setLiveIncidents(prev => {
      const next = [inc, ...prev.filter(i => i.incidentId !== inc.incidentId)]
      return next.slice(0, 100)
    })
    queryClient.invalidateQueries({ queryKey: ['health'] })
  }, [queryClient])

  const handleRemediation = useCallback((data: unknown) => {
    const rec = data as RemediationRecord
    setLiveRemediations(prev => {
      const next = [rec, ...prev.filter(r => r.recordId !== rec.recordId)]
      return next.slice(0, 50)
    })
  }, [])

  useEventStream('/api/v1/stream', { incident: handleIncident, remediation: handleRemediation })

  // Merge SSE data with polling (SSE data is more recent)
  const mergedIncidents = [
    ...liveIncidents,
    ...incidents.filter(i => !liveIncidents.find(l => l.incidentId === i.incidentId)),
  ].slice(0, 50)

  const mergedRemediations = [
    ...liveRemediations,
    ...remediations.filter(r => !liveRemediations.find(l => l.recordId === r.recordId)),
  ].slice(0, 20)

  const lastRefresh  = Math.max(h_at, i_at, r_at, s_at)
  const criticalCount = health.filter(h => h.status === 'CRITICAL').length
  const warningCount  = health.filter(h => h.status === 'WARNING').length

  return (
    <div className="min-h-screen bg-surface-900 p-4 md:p-6">

      {/* ── Header ──────────────────────────────────────────────────── */}
      <header className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <ShieldAlert className="w-7 h-7 text-blue-400" />
          <div>
            <h1 className="text-xl font-bold text-slate-100">AIOps Operations Centre</h1>
            <p className="text-xs text-slate-500">AML Platform · Real-time SSE + 5 s polling</p>
          </div>
        </div>

        <div className="flex items-center gap-4">
          {criticalCount > 0 && (
            <span className="flex items-center gap-1.5 text-red-400 text-sm font-semibold animate-pulse">
              <span className="w-2 h-2 rounded-full bg-red-400 inline-block" />
              {criticalCount} CRITICAL
            </span>
          )}
          {warningCount > 0 && (
            <span className="flex items-center gap-1.5 text-yellow-400 text-sm">
              <span className="w-2 h-2 rounded-full bg-yellow-400 inline-block" />
              {warningCount} WARNING
            </span>
          )}
          <div className="flex items-center gap-1.5 text-slate-500 text-xs">
            <RefreshCw className="w-3 h-3" />
            {lastRefresh ? format(new Date(lastRefresh), 'HH:mm:ss') : '—'}
          </div>
        </div>
      </header>

      {/* ── Service Health ───────────────────────────────────────────── */}
      <section className="mb-6 space-y-3">
        {(['aml', 'aiops'] as const).map(ns => {
          const group = health.filter(h =>
            (h.namespace ?? (['customer-kyc','transaction-monitoring','case-management'].includes(h.service) ? 'aml' : 'aiops')) === ns
          )
          if (group.length === 0) return null
          return (
            <div key={ns}>
              <p className={clsx(
                'text-[10px] font-semibold uppercase tracking-widest mb-1.5',
                ns === 'aiops' ? 'text-purple-400' : 'text-blue-400',
              )}>
                {ns === 'aiops' ? 'AIOps Services' : 'AML Services'}
                <span className="ml-1.5 text-slate-500 font-normal normal-case tracking-normal">
                  ({group.filter(h => h.status === 'CRITICAL').length} critical,{' '}
                   {group.filter(h => h.status === 'WARNING').length} warning)
                </span>
              </p>
              <div className={clsx(
                'grid gap-2',
                ns === 'aml'
                  ? 'grid-cols-1 sm:grid-cols-3'
                  : 'grid-cols-2 sm:grid-cols-4',
              )}>
                {group.map(h => <ServiceHealthCard key={h.service} health={h} compact={ns === 'aiops'} />)}
              </div>
            </div>
          )
        })}
      </section>

      {/* ── Live Anomaly Chart ───────────────────────────────────────── */}
      <div className="mb-6">
        <Card title="Live Anomaly Scores (real-time · 30 s buckets)">
          <div className="flex items-center gap-1.5 mb-2 text-xs text-green-400">
            <Activity className="w-3 h-3" />
            <span>SSE live · {liveIncidents.length} events received</span>
          </div>
          <LiveAnomalyChart incidents={mergedIncidents} />
        </Card>
      </div>

      {/* ── Main Grid ────────────────────────────────────────────────── */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-4 mb-6">

        <div className="xl:col-span-2">
          <Card title={`Active Incidents (${mergedIncidents.length})`}>
            <IncidentTable incidents={mergedIncidents} />
          </Card>
        </div>

        <div className="flex flex-col gap-4">
          <Card title="Outcome Summary">
            <OutcomeChart summary={summary} />
          </Card>
          <Card title={`Remediation Log (${mergedRemediations.length})`}>
            <RemediationLog remediations={mergedRemediations} />
          </Card>
        </div>
      </div>

      {/* ── LLM Analysis ─────────────────────────────────────────────── */}
      <div>
        <Card title="LLM Analysis — Claude-powered incident explanation">
          <div className="flex items-center gap-1.5 mb-3 text-xs text-purple-400">
            <Brain className="w-3 h-3" />
            <span>Inference: {MODEL_LABEL} via Ollama · Training: Qwen2.5-0.5B LoRA (PEFT)</span>
          </div>
          <LlmAnalysisPanel />
        </Card>
      </div>

    </div>
  )
}

const MODEL_LABEL = 'qwen2.5:3b'

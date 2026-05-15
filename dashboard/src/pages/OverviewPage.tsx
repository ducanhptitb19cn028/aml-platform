import { useQuery } from '@tanstack/react-query'
import { Activity, RefreshCw } from 'lucide-react'
import { format } from 'date-fns'
import clsx from 'clsx'
import { api } from '../api/client'
import type { Incident, RemediationRecord, ServiceHealth } from '../types'
import ServiceHealthCard from '../components/ServiceHealthCard'
import LiveAnomalyChart from '../components/LiveAnomalyChart'
import IncidentTable from '../components/IncidentTable'
import RemediationLog from '../components/RemediationLog'
import OutcomeChart from '../components/OutcomeChart'

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-surface-800 rounded-lg p-4 border border-surface-700">
      <h2 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-3">
        {title}
      </h2>
      {children}
    </div>
  )
}

interface Props {
  health:           ServiceHealth[]
  liveIncidents:    Incident[]
  liveRemediations: RemediationRecord[]
}

export default function OverviewPage({ health, liveIncidents, liveRemediations }: Props) {
  const { data: incidents    = [], dataUpdatedAt: i_at } = useQuery({ queryKey: ['incidents'],    queryFn: api.incidents,      refetchInterval: 5_000 })
  const { data: remediations = [], dataUpdatedAt: r_at } = useQuery({ queryKey: ['remediations'], queryFn: api.remediations,   refetchInterval: 5_000 })
  const { data: summary = { RESOLVED: 0, NO_EFFECT: 0, DEGRADED_FURTHER: 0 } } =
    useQuery({ queryKey: ['summary'], queryFn: api.outcomeSummary, refetchInterval: 10_000 })

  const mergedIncidents = [
    ...liveIncidents,
    ...incidents.filter(i => !liveIncidents.find(l => l.incidentId === i.incidentId)),
  ].slice(0, 50)

  const mergedRemediations = [
    ...liveRemediations,
    ...remediations.filter(r => !liveRemediations.find(l => l.recordId === r.recordId)),
  ].slice(0, 20)

  const lastRefresh = Math.max(i_at, r_at)
  const totalResolved = summary.RESOLVED ?? 0
  const totalOutcomes = (summary.RESOLVED ?? 0) + (summary.NO_EFFECT ?? 0) + (summary.DEGRADED_FURTHER ?? 0)
  const successRate = totalOutcomes > 0 ? Math.round((totalResolved / totalOutcomes) * 100) : 0

  return (
    <div className="space-y-5">

      {/* ── Stats row ───────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {[
          { label: 'Total Incidents',  value: mergedIncidents.length,                          color: 'text-blue-400' },
          { label: 'P1 Active',        value: mergedIncidents.filter(i => i.anomalyScore >= 0.9).length, color: 'text-red-400' },
          { label: 'Remediations',     value: mergedRemediations.length,                        color: 'text-purple-400' },
          { label: 'Resolved %',       value: `${successRate}%`,                               color: 'text-green-400' },
        ].map(({ label, value, color }) => (
          <div key={label} className="bg-surface-800 rounded-lg p-3 border border-surface-700">
            <p className="text-xs text-slate-500">{label}</p>
            <p className={`text-2xl font-bold mt-0.5 ${color}`}>{value}</p>
          </div>
        ))}
      </div>

      {/* ── Service health ──────────────────────────────────────────── */}
      <section className="space-y-3">
        {health.length === 0
          ? (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
              {Array.from({ length: 8 }).map((_, n) => (
                <div key={n} className="bg-surface-800 rounded-lg p-3 border border-surface-700 animate-pulse h-16" />
              ))}
            </div>
          )
          : (['aml', 'aiops'] as const).map(ns => {
              const group = health.filter(h =>
                (h.namespace ?? (['customer-kyc','transaction-monitoring','case-management'].includes(h.service)
                  ? 'aml' : 'aiops')) === ns
              )
              if (group.length === 0) return null
              return (
                <div key={ns}>
                  <p className={clsx(
                    'text-[10px] font-semibold uppercase tracking-widest mb-1.5',
                    ns === 'aiops' ? 'text-purple-400' : 'text-blue-400',
                  )}>
                    {ns === 'aiops' ? 'AIOps Services' : 'AML Services'}
                  </p>
                  <div className={clsx(
                    'grid gap-2',
                    ns === 'aml' ? 'grid-cols-1 sm:grid-cols-3' : 'grid-cols-2 sm:grid-cols-4',
                  )}>
                    {group.map(h => <ServiceHealthCard key={h.service} health={h} compact={ns === 'aiops'} />)}
                  </div>
                </div>
              )
            })
        }
      </section>

      {/* ── Live anomaly chart ───────────────────────────────────────── */}
      <Card title="Live Anomaly Scores — 30 s buckets">
        <div className="flex items-center gap-1.5 mb-2 text-xs text-green-400">
          <Activity className="w-3 h-3" />
          <span>SSE live · {liveIncidents.length} events received</span>
          {lastRefresh > 0 && (
            <span className="ml-auto flex items-center gap-1 text-slate-500">
              <RefreshCw className="w-3 h-3" />
              {format(new Date(lastRefresh), 'HH:mm:ss')}
            </span>
          )}
        </div>
        <LiveAnomalyChart incidents={mergedIncidents} />
      </Card>

      {/* ── Incidents + side panels ─────────────────────────────────── */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-4">
        <div className="xl:col-span-2">
          <Card title={`Recent Incidents (${mergedIncidents.length})`}>
            <IncidentTable incidents={mergedIncidents.slice(0, 10)} />
          </Card>
        </div>
        <div className="flex flex-col gap-4">
          <Card title="Outcome Summary">
            <OutcomeChart summary={summary} />
          </Card>
          <Card title={`Remediation Log (${mergedRemediations.length})`}>
            <RemediationLog remediations={mergedRemediations.slice(0, 5)} />
          </Card>
        </div>
      </div>

    </div>
  )
}

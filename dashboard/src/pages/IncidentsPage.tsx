import { useState, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { RefreshCw } from 'lucide-react'
import { format, formatDistanceToNow } from 'date-fns'
import { api } from '../api/client'
import type { Incident } from '../types'
import SeverityBadge, { scoreToSeverity } from '../components/SeverityBadge'

const SERVICES = ['all', 'transaction-monitoring', 'case-management', 'customer-kyc'] as const
type ServiceFilter = (typeof SERVICES)[number]

const SEVERITIES = ['all', 'P1', 'P2', 'P3', 'P4'] as const
type SevFilter = (typeof SEVERITIES)[number]

interface Props {
  liveIncidents: Incident[]
}

export default function IncidentsPage({ liveIncidents }: Props) {
  const [sevFilter, setSevFilter]     = useState<SevFilter>('all')
  const [svcFilter, setSvcFilter]     = useState<ServiceFilter>('all')

  const { data: polled = [], dataUpdatedAt, isFetching } =
    useQuery({ queryKey: ['incidents'], queryFn: api.incidents, refetchInterval: 5_000 })

  const merged = useMemo(() => [
    ...liveIncidents,
    ...polled.filter(i => !liveIncidents.find(l => l.incidentId === i.incidentId)),
  ].slice(0, 200), [liveIncidents, polled])

  const filtered = useMemo(() => merged.filter(inc => {
    const svc = inc.affectedServices?.[0] ?? ''
    const sev = scoreToSeverity(inc.anomalyScore)
    if (sevFilter !== 'all' && sev !== sevFilter) return false
    if (svcFilter !== 'all' && svc !== svcFilter) return false
    return true
  }), [merged, sevFilter, svcFilter])

  return (
    <div className="space-y-4">

      {/* ── Header ─────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-lg font-bold text-slate-100">Incidents</h1>
          <p className="text-xs text-slate-500 mt-0.5">
            {filtered.length} of {merged.length} · SSE + 5 s polling
          </p>
        </div>
        <div className="flex items-center gap-1.5 text-xs text-slate-500">
          <RefreshCw className={`w-3 h-3 ${isFetching ? 'animate-spin text-blue-400' : ''}`} />
          {dataUpdatedAt ? format(new Date(dataUpdatedAt), 'HH:mm:ss') : '—'}
        </div>
      </div>

      {/* ── Filters ────────────────────────────────────────────────── */}
      <div className="flex flex-wrap gap-3">
        <div className="flex gap-1">
          {SEVERITIES.map(s => (
            <button
              key={s}
              onClick={() => setSevFilter(s)}
              className={`px-2.5 py-1 rounded text-xs font-semibold transition-colors ${
                sevFilter === s
                  ? 'bg-blue-600 text-white'
                  : 'bg-surface-700 text-slate-400 hover:text-slate-200'
              }`}
            >
              {s === 'all' ? 'All' : s}
            </button>
          ))}
        </div>
        <select
          value={svcFilter}
          onChange={e => setSvcFilter(e.target.value as ServiceFilter)}
          className="bg-surface-700 text-slate-300 text-xs rounded px-2 py-1 border border-surface-600 focus:outline-none"
        >
          {SERVICES.map(s => (
            <option key={s} value={s}>{s === 'all' ? 'All services' : s}</option>
          ))}
        </select>
      </div>

      {/* ── Table ──────────────────────────────────────────────────── */}
      <div className="bg-surface-800 rounded-lg border border-surface-700 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-slate-400 border-b border-surface-700 bg-surface-900/50">
                {['Time', 'Service', 'Sev', 'Score', 'Conf', 'Breach ETA', 'Root Cause', 'Affected'].map(h => (
                  <th key={h} className="px-4 py-2.5 text-xs font-medium whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-4 py-10 text-center text-slate-500 text-sm">
                    No incidents match the current filter.
                  </td>
                </tr>
              ) : (
                filtered.map(inc => {
                  const sev      = scoreToSeverity(inc.anomalyScore)
                  const service  = inc.affectedServices?.[0] ?? '—'
                  const rootCause = inc.rootCauseRanking?.[0]?.component ?? '—'
                  const ago      = inc.detectedAt
                    ? formatDistanceToNow(new Date(inc.detectedAt), { addSuffix: true })
                    : '—'
                  const isLive = liveIncidents.some(l => l.incidentId === inc.incidentId)

                  return (
                    <tr
                      key={inc.incidentId}
                      className="border-b border-surface-700 hover:bg-surface-700/50 transition-colors"
                    >
                      <td className="px-4 py-2.5 text-slate-400 text-xs whitespace-nowrap">
                        {isLive && <span className="inline-block w-1.5 h-1.5 rounded-full bg-green-400 mr-1.5 align-middle" />}
                        {ago}
                      </td>
                      <td className="px-4 py-2.5 font-mono text-xs text-slate-200 whitespace-nowrap">{service}</td>
                      <td className="px-4 py-2.5"><SeverityBadge severity={sev} /></td>
                      <td className="px-4 py-2.5 tabular-nums text-slate-200">{inc.anomalyScore.toFixed(3)}</td>
                      <td className="px-4 py-2.5 tabular-nums text-slate-300">{(inc.confidence * 100).toFixed(0)}%</td>
                      <td className="px-4 py-2.5 tabular-nums text-slate-300 whitespace-nowrap">
                        {inc.breachEtaMinutes >= 999 ? '—' : `${inc.breachEtaMinutes} min`}
                      </td>
                      <td className="px-4 py-2.5 font-mono text-xs text-slate-300 max-w-xs truncate">{rootCause}</td>
                      <td className="px-4 py-2.5 text-xs text-slate-400">
                        {inc.affectedServices?.join(', ') ?? '—'}
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

    </div>
  )
}

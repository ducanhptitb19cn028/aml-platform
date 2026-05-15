import { useQuery } from '@tanstack/react-query'
import { formatDistanceToNow } from 'date-fns'
import clsx from 'clsx'
import { api } from '../api/client'
import type { IncidentOutcome } from '../types'
import OutcomeChart from '../components/OutcomeChart'

const LABEL_STYLE: Record<string, string> = {
  RESOLVED:         'text-green-400  bg-green-900/30  border-green-800',
  NO_EFFECT:        'text-slate-400  bg-surface-700   border-surface-600',
  DEGRADED_FURTHER: 'text-red-400    bg-red-900/30    border-red-800',
}

export default function OutcomesPage() {
  const { data: summary = { RESOLVED: 0, NO_EFFECT: 0, DEGRADED_FURTHER: 0 } } =
    useQuery({ queryKey: ['summary'], queryFn: api.outcomeSummary, refetchInterval: 10_000 })

  const { data: outcomes = [] } =
    useQuery<IncidentOutcome[]>({ queryKey: ['outcomes'], queryFn: api.outcomes, refetchInterval: 10_000 })

  const total      = outcomes.length
  const resolved   = outcomes.filter(o => o.label === 'RESOLVED').length
  const degraded   = outcomes.filter(o => o.label === 'DEGRADED_FURTHER').length
  const successPct = total > 0 ? Math.round((resolved / total) * 100) : 0

  const avgSloGain = outcomes.length > 0
    ? outcomes.reduce((acc, o) => acc + (o.sloAfter - o.sloBefore), 0) / outcomes.length
    : 0

  return (
    <div className="space-y-5">

      {/* ── Header ─────────────────────────────────────────────────── */}
      <div>
        <h1 className="text-lg font-bold text-slate-100">Outcomes</h1>
        <p className="text-xs text-slate-500 mt-0.5">
          Post-remediation feedback — used to train the LoRA adapter
        </p>
      </div>

      {/* ── Stats ──────────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {[
          { label: 'Total Outcomes',  value: total,         color: 'text-slate-200' },
          { label: 'Resolved',        value: resolved,      color: 'text-green-400' },
          { label: 'Degraded',        value: degraded,      color: 'text-red-400' },
          { label: 'Success Rate',    value: `${successPct}%`, color: 'text-blue-400' },
        ].map(({ label, value, color }) => (
          <div key={label} className="bg-surface-800 rounded-lg p-3 border border-surface-700">
            <p className="text-xs text-slate-500">{label}</p>
            <p className={`text-2xl font-bold mt-0.5 ${color}`}>{value}</p>
          </div>
        ))}
      </div>

      {/* ── Chart ──────────────────────────────────────────────────── */}
      <div className="bg-surface-800 rounded-lg border border-surface-700 p-4">
        <h2 className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-4">
          Outcome Distribution
        </h2>
        <div className="max-w-md">
          <OutcomeChart summary={summary} />
        </div>
        {avgSloGain !== 0 && (
          <p className="mt-3 text-xs text-slate-400">
            Average SLO change after remediation:{' '}
            <span className={avgSloGain >= 0 ? 'text-green-400' : 'text-red-400'}>
              {avgSloGain >= 0 ? '+' : ''}{(avgSloGain * 100).toFixed(1)}%
            </span>
          </p>
        )}
      </div>

      {/* ── Outcome list ───────────────────────────────────────────── */}
      <div className="bg-surface-800 rounded-lg border border-surface-700 overflow-hidden">
        <div className="px-4 py-3 border-b border-surface-700">
          <h2 className="text-xs font-semibold text-slate-400 uppercase tracking-wider">
            All Outcomes ({outcomes.length})
          </h2>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-slate-400 border-b border-surface-700 bg-surface-900/50">
                {['Time', 'Incident', 'Label', 'SLO Before', 'SLO After', 'SLO Δ'].map(h => (
                  <th key={h} className="px-4 py-2.5 text-xs font-medium whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {outcomes.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-10 text-center text-slate-500 text-sm">
                    No outcomes recorded yet — they are written by the feedback-service after each remediation.
                  </td>
                </tr>
              ) : (
                outcomes.map(o => {
                  const sloDelta = o.sloAfter - o.sloBefore
                  const ago = o.evaluatedAt
                    ? formatDistanceToNow(new Date(o.evaluatedAt), { addSuffix: true })
                    : '—'

                  return (
                    <tr
                      key={o.outcomeId}
                      className="border-b border-surface-700 hover:bg-surface-700/50 transition-colors"
                    >
                      <td className="px-4 py-2.5 text-xs text-slate-400 whitespace-nowrap">{ago}</td>
                      <td className="px-4 py-2.5 font-mono text-xs text-slate-300 max-w-[140px] truncate">
                        {o.incidentId}
                      </td>
                      <td className="px-4 py-2.5">
                        <span className={clsx(
                          'px-1.5 py-0.5 rounded border text-xs font-semibold uppercase',
                          LABEL_STYLE[o.label] ?? 'text-slate-400 bg-surface-700 border-surface-600',
                        )}>
                          {o.label === 'DEGRADED_FURTHER' ? 'DEGRADED' : o.label}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 tabular-nums text-slate-300">
                        {(o.sloBefore * 100).toFixed(1)}%
                      </td>
                      <td className="px-4 py-2.5 tabular-nums text-slate-300">
                        {(o.sloAfter * 100).toFixed(1)}%
                      </td>
                      <td className={clsx(
                        'px-4 py-2.5 tabular-nums font-semibold',
                        sloDelta >= 0 ? 'text-green-400' : 'text-red-400',
                      )}>
                        {sloDelta >= 0 ? '+' : ''}{(sloDelta * 100).toFixed(1)}%
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

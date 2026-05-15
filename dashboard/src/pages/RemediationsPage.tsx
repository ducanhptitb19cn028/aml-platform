import { useState, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { RefreshCw } from 'lucide-react'
import { format, formatDistanceToNow } from 'date-fns'
import clsx from 'clsx'
import { api } from '../api/client'
import type { RemediationRecord } from '../types'

type Status = RemediationRecord['status'] | 'ALL'
const STATUSES: Status[] = ['ALL', 'COMPLETED', 'EXECUTING', 'PENDING', 'FAILED', 'VETOED']

const STATUS_STYLE: Record<string, string> = {
  COMPLETED: 'text-green-400 bg-green-900/30 border-green-800',
  FAILED:    'text-red-400   bg-red-900/30   border-red-800',
  VETOED:    'text-slate-400 bg-surface-700  border-surface-600',
  EXECUTING: 'text-yellow-400 bg-yellow-900/30 border-yellow-800',
  PENDING:   'text-blue-400  bg-blue-900/30  border-blue-800',
}

interface Props {
  liveRemediations: RemediationRecord[]
}

export default function RemediationsPage({ liveRemediations }: Props) {
  const [statusFilter, setStatusFilter] = useState<Status>('ALL')

  const { data: polled = [], dataUpdatedAt, isFetching } =
    useQuery({ queryKey: ['remediations'], queryFn: api.remediations, refetchInterval: 5_000 })

  const merged = useMemo(() => [
    ...liveRemediations,
    ...polled.filter(r => !liveRemediations.find(l => l.recordId === r.recordId)),
  ].slice(0, 100), [liveRemediations, polled])

  const filtered = useMemo(() =>
    statusFilter === 'ALL' ? merged : merged.filter(r => r.status === statusFilter),
    [merged, statusFilter],
  )

  // Stats
  const total     = merged.length
  const completed = merged.filter(r => r.status === 'COMPLETED').length
  const failed    = merged.filter(r => r.status === 'FAILED').length
  const successPct = total > 0 ? Math.round((completed / total) * 100) : 0

  return (
    <div className="space-y-4">

      {/* ── Header ─────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-lg font-bold text-slate-100">Remediation Actions</h1>
          <p className="text-xs text-slate-500 mt-0.5">Automated actions taken by the decision engine</p>
        </div>
        <div className="flex items-center gap-1.5 text-xs text-slate-500">
          <RefreshCw className={`w-3 h-3 ${isFetching ? 'animate-spin text-blue-400' : ''}`} />
          {dataUpdatedAt ? format(new Date(dataUpdatedAt), 'HH:mm:ss') : '—'}
        </div>
      </div>

      {/* ── Stats ──────────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {[
          { label: 'Total Actions',  value: total,      color: 'text-slate-200' },
          { label: 'Completed',      value: completed,  color: 'text-green-400' },
          { label: 'Failed',         value: failed,     color: 'text-red-400' },
          { label: 'Success Rate',   value: `${successPct}%`, color: 'text-blue-400' },
        ].map(({ label, value, color }) => (
          <div key={label} className="bg-surface-800 rounded-lg p-3 border border-surface-700">
            <p className="text-xs text-slate-500">{label}</p>
            <p className={`text-2xl font-bold mt-0.5 ${color}`}>{value}</p>
          </div>
        ))}
      </div>

      {/* ── Status filter ──────────────────────────────────────────── */}
      <div className="flex flex-wrap gap-1.5">
        {STATUSES.map(s => (
          <button
            key={s}
            onClick={() => setStatusFilter(s)}
            className={clsx(
              'px-3 py-1 rounded text-xs font-medium transition-colors border',
              statusFilter === s
                ? 'bg-blue-600 text-white border-blue-500'
                : 'bg-surface-700 text-slate-400 border-surface-600 hover:text-slate-200',
            )}
          >
            {s}
          </button>
        ))}
      </div>

      {/* ── Remediation list ───────────────────────────────────────── */}
      <div className="bg-surface-800 rounded-lg border border-surface-700 divide-y divide-surface-700">
        {filtered.length === 0 ? (
          <p className="px-4 py-10 text-center text-slate-500 text-sm">No remediation actions match the filter.</p>
        ) : (
          filtered.map(r => {
            const isLive = liveRemediations.some(l => l.recordId === r.recordId)
            const ago = r.executedAt
              ? formatDistanceToNow(new Date(r.executedAt), { addSuffix: true })
              : '—'

            return (
              <div key={r.recordId} className="px-4 py-3 flex flex-col sm:flex-row sm:items-center gap-2">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    {isLive && <span className="w-1.5 h-1.5 rounded-full bg-green-400 flex-shrink-0" />}
                    <span className="font-mono text-xs text-slate-200 font-medium">{r.action}</span>
                    <span className="text-slate-500 text-xs">→</span>
                    <span className="font-mono text-xs text-blue-300">{r.targetService}</span>
                    <span className="text-slate-500 text-xs">({r.targetNamespace})</span>
                  </div>
                  {(r.preState || r.postState) && (
                    <div className="mt-1 text-xs text-slate-400 flex gap-3">
                      {r.preState  && <span>Before: <span className="text-slate-300">{r.preState}</span></span>}
                      {r.postState && <span>After: <span className="text-slate-300">{r.postState}</span></span>}
                    </div>
                  )}
                </div>
                <div className="flex items-center gap-3 flex-shrink-0">
                  <span className={clsx(
                    'px-2 py-0.5 rounded text-xs font-semibold border',
                    STATUS_STYLE[r.status] ?? 'text-slate-400 bg-surface-700 border-surface-600',
                  )}>
                    {r.status}
                  </span>
                  <span className="text-xs text-slate-500 whitespace-nowrap">{ago}</span>
                </div>
              </div>
            )
          })
        )}
      </div>

    </div>
  )
}

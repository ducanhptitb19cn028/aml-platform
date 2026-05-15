import { formatDistanceToNow } from 'date-fns'
import clsx from 'clsx'
import type { RemediationRecord } from '../types'

const statusStyle: Record<string, string> = {
  COMPLETED: 'text-green-400',
  FAILED:    'text-red-400',
  VETOED:    'text-slate-400',
  EXECUTING: 'text-yellow-400',
  PENDING:   'text-blue-400',
}

interface Props {
  remediations: RemediationRecord[]
}

export default function RemediationLog({ remediations }: Props) {
  if (remediations.length === 0) {
    return (
      <p className="text-slate-500 text-sm py-4 text-center">
        No remediation actions yet.
      </p>
    )
  }

  return (
    <ul className="space-y-2 max-h-64 overflow-y-auto pr-1">
      {remediations.map((r) => {
        const ago = r.executedAt
          ? formatDistanceToNow(new Date(r.executedAt), { addSuffix: true })
          : '—'
        return (
          <li
            key={r.recordId}
            className="flex items-start gap-3 bg-surface-700 rounded p-2 text-sm"
          >
            <div className="flex-1 min-w-0">
              <span className="font-mono text-xs text-slate-300">{r.action}</span>
              <span className="mx-1 text-slate-500">→</span>
              <span className="font-mono text-xs text-blue-300">{r.targetService}</span>
            </div>
            <span className={clsx('text-xs font-semibold flex-shrink-0', statusStyle[r.status] ?? 'text-slate-400')}>
              {r.status}
            </span>
            <span className="text-xs text-slate-500 flex-shrink-0 whitespace-nowrap">{ago}</span>
          </li>
        )
      })}
    </ul>
  )
}

import { Activity, AlertTriangle, XCircle } from 'lucide-react'
import clsx from 'clsx'
import type { ServiceHealth } from '../types'

const config = {
  OK:       { icon: Activity,      color: 'text-green-400',  bg: 'border-green-800',  label: 'Healthy' },
  WARNING:  { icon: AlertTriangle, color: 'text-yellow-400', bg: 'border-yellow-800', label: 'Degraded' },
  CRITICAL: { icon: XCircle,       color: 'text-red-400',    bg: 'border-red-800',    label: 'Critical' },
}

interface Props {
  health: ServiceHealth
  compact?: boolean
}

export default function ServiceHealthCard({ health, compact = false }: Props) {
  const { icon: Icon, color, bg, label } = config[health.status]
  const ns = health.namespace ?? (
    ['customer-kyc', 'transaction-monitoring', 'case-management'].includes(health.service)
      ? 'aml' : 'aiops'
  )

  return (
    <div className={clsx(
      'bg-surface-800 rounded-lg border-l-4 flex items-center gap-3',
      compact ? 'p-2.5' : 'p-4',
      bg,
    )}>
      <Icon className={clsx('flex-shrink-0', compact ? 'w-5 h-5' : 'w-8 h-8', color)} />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1.5">
          <p className={clsx('text-slate-200 truncate font-medium', compact ? 'text-xs' : 'text-sm')}>
            {health.service}
          </p>
          <span className={clsx(
            'text-[9px] px-1 py-0.5 rounded font-mono flex-shrink-0',
            ns === 'aiops' ? 'bg-purple-900/60 text-purple-300' : 'bg-blue-900/60 text-blue-300',
          )}>
            {ns}
          </span>
        </div>
        <p className={clsx('font-semibold', compact ? 'text-sm' : 'text-lg', color)}>{label}</p>
        <p className="text-xs text-slate-500">score {health.lastAnomalyScore.toFixed(3)}</p>
      </div>
    </div>
  )
}

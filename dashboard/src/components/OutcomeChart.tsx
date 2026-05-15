import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  Cell,
} from 'recharts'
import type { OutcomeSummary } from '../types'

const COLORS: Record<string, string> = {
  RESOLVED:         '#4ade80',
  NO_EFFECT:        '#94a3b8',
  DEGRADED_FURTHER: '#f87171',
}

interface Props {
  summary: OutcomeSummary
}

export default function OutcomeChart({ summary }: Props) {
  const data = Object.entries(summary).map(([label, count]) => ({ label, count }))

  if (data.length === 0 || data.every(d => d.count === 0)) {
    return (
      <p className="text-slate-500 text-sm py-4 text-center">
        No outcomes recorded yet.
      </p>
    )
  }

  return (
    <ResponsiveContainer width="100%" height={160}>
      <BarChart data={data} margin={{ top: 4, right: 4, left: -20, bottom: 0 }}>
        <XAxis
          dataKey="label"
          tick={{ fill: '#94a3b8', fontSize: 10 }}
          tickFormatter={(v: string) =>
            v === 'DEGRADED_FURTHER' ? 'DEGRADED' : v
          }
        />
        <YAxis tick={{ fill: '#94a3b8', fontSize: 10 }} allowDecimals={false} />
        <Tooltip
          contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 6 }}
          labelStyle={{ color: '#cbd5e1' }}
          itemStyle={{ color: '#e2e8f0' }}
        />
        <Bar dataKey="count" radius={[4, 4, 0, 0]}>
          {data.map((entry) => (
            <Cell key={entry.label} fill={COLORS[entry.label] ?? '#64748b'} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

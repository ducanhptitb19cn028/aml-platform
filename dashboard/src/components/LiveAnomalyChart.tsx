import {
  LineChart, Line, XAxis, YAxis, CartesianGrid,
  Tooltip, Legend, ResponsiveContainer, ReferenceLine,
} from 'recharts'
import { format } from 'date-fns'
import type { Incident } from '../types'

interface DataPoint {
  time:                    string
  ts:                      number
  'customer-kyc':          number | null
  'transaction-monitoring': number | null
  'case-management':       number | null
}

interface Props {
  incidents: Incident[]
}

const SERVICE_COLORS: Record<string, string> = {
  'customer-kyc':           '#60a5fa',
  'transaction-monitoring': '#f59e0b',
  'case-management':        '#34d399',
}

function buildSeries(incidents: Incident[]): DataPoint[] {
  // Group incidents into 30-second buckets, pick max score per service per bucket
  const buckets = new Map<number, Record<string, number>>()
  const BUCKET_MS = 30_000

  const sorted = [...incidents].sort(
    (a, b) => new Date(a.detectedAt).getTime() - new Date(b.detectedAt).getTime()
  )

  for (const inc of sorted) {
    const svc   = inc.affectedServices?.[0]
    if (!svc) continue
    const ts    = new Date(inc.detectedAt).getTime()
    const key   = Math.floor(ts / BUCKET_MS) * BUCKET_MS
    const bucket = buckets.get(key) ?? {}
    bucket[svc] = Math.max(bucket[svc] ?? 0, inc.anomalyScore)
    buckets.set(key, bucket)
  }

  return Array.from(buckets.entries())
    .sort(([a], [b]) => a - b)
    .slice(-20) // last 20 buckets
    .map(([ts, scores]) => ({
      time:                    format(new Date(ts), 'HH:mm:ss'),
      ts,
      'customer-kyc':           scores['customer-kyc'] ?? null,
      'transaction-monitoring': scores['transaction-monitoring'] ?? null,
      'case-management':        scores['case-management'] ?? null,
    }))
}

export default function LiveAnomalyChart({ incidents }: Props) {
  const data = buildSeries(incidents)

  if (data.length === 0) {
    return (
      <p className="text-slate-500 text-sm py-8 text-center">
        Waiting for anomaly data…
      </p>
    )
  }

  return (
    <ResponsiveContainer width="100%" height={200}>
      <LineChart data={data} margin={{ top: 4, right: 8, left: -20, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
        <XAxis dataKey="time" tick={{ fontSize: 11, fill: '#94a3b8' }} />
        <YAxis domain={[0, 1]} tick={{ fontSize: 11, fill: '#94a3b8' }} />
        <Tooltip
          contentStyle={{ background: '#1e293b', border: '1px solid #334155', fontSize: 12 }}
          labelStyle={{ color: '#94a3b8' }}
        />
        <Legend wrapperStyle={{ fontSize: 11 }} />
        <ReferenceLine y={0.75} stroke="#ef4444" strokeDasharray="4 2" label={{ value: 'P1', fill: '#ef4444', fontSize: 10 }} />
        <ReferenceLine y={0.5}  stroke="#f59e0b" strokeDasharray="4 2" label={{ value: 'P2', fill: '#f59e0b', fontSize: 10 }} />
        {Object.entries(SERVICE_COLORS).map(([svc, color]) => (
          <Line
            key={svc}
            type="monotone"
            dataKey={svc}
            stroke={color}
            strokeWidth={2}
            dot={false}
            connectNulls
          />
        ))}
      </LineChart>
    </ResponsiveContainer>
  )
}

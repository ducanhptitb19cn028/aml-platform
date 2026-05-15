import { formatDistanceToNow } from 'date-fns'
import type { Incident } from '../types'
import SeverityBadge, { scoreToSeverity } from './SeverityBadge'

interface Props {
  incidents: Incident[]
}

export default function IncidentTable({ incidents }: Props) {
  if (incidents.length === 0) {
    return (
      <p className="text-slate-500 text-sm py-8 text-center">
        No incidents detected.
      </p>
    )
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-slate-400 border-b border-surface-700">
            <th className="pb-2 pr-4 font-medium">Time</th>
            <th className="pb-2 pr-4 font-medium">Service</th>
            <th className="pb-2 pr-4 font-medium">Sev</th>
            <th className="pb-2 pr-4 font-medium">Score</th>
            <th className="pb-2 pr-4 font-medium">Confidence</th>
            <th className="pb-2 pr-4 font-medium">Breach ETA</th>
            <th className="pb-2    font-medium">Root Cause</th>
          </tr>
        </thead>
        <tbody>
          {incidents.map((inc) => {
            const severity    = scoreToSeverity(inc.anomalyScore)
            const rootCause   = inc.rootCauseRanking?.[0]?.component ?? '—'
            const service     = inc.affectedServices?.[0] ?? '—'
            const ago         = inc.detectedAt
              ? formatDistanceToNow(new Date(inc.detectedAt), { addSuffix: true })
              : '—'

            return (
              <tr
                key={inc.incidentId}
                className="border-b border-surface-700 hover:bg-surface-700 transition-colors"
              >
                <td className="py-2 pr-4 text-slate-400 whitespace-nowrap">{ago}</td>
                <td className="py-2 pr-4 font-mono text-xs text-slate-200">{service}</td>
                <td className="py-2 pr-4"><SeverityBadge severity={severity} /></td>
                <td className="py-2 pr-4 tabular-nums">{inc.anomalyScore.toFixed(3)}</td>
                <td className="py-2 pr-4 tabular-nums">{(inc.confidence * 100).toFixed(0)}%</td>
                <td className="py-2 pr-4 tabular-nums">
                  {inc.breachEtaMinutes >= 999 ? '—' : `${inc.breachEtaMinutes} min`}
                </td>
                <td className="py-2 font-mono text-xs text-slate-300">{rootCause}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

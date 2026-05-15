import clsx from 'clsx'

export type Severity = 'P1' | 'P2' | 'P3' | 'P4'

export function scoreToSeverity(score: number): Severity {
  if (score >= 0.90) return 'P1'
  if (score >= 0.75) return 'P2'
  if (score >= 0.50) return 'P3'
  return 'P4'
}

const styles: Record<Severity, string> = {
  P1: 'bg-red-900 text-red-300 border-red-700',
  P2: 'bg-orange-900 text-orange-300 border-orange-700',
  P3: 'bg-yellow-900 text-yellow-300 border-yellow-700',
  P4: 'bg-green-900 text-green-300 border-green-700',
}

interface Props {
  severity: Severity
}

export default function SeverityBadge({ severity }: Props) {
  return (
    <span className={clsx(
      'inline-flex items-center px-2 py-0.5 rounded text-xs font-bold border',
      styles[severity],
    )}>
      {severity}
    </span>
  )
}

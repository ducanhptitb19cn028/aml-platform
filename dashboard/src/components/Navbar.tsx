import { useState, useEffect } from 'react'
import { ShieldAlert, LayoutDashboard, AlertTriangle, Wrench, Brain, BarChart3, Clock, Layers } from 'lucide-react'
import clsx from 'clsx'

function useLiveClock() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(id)
  }, [])
  return now
}

export type Page = 'overview' | 'incidents' | 'remediations' | 'llm' | 'outcomes' | 'melt'

interface NavItem {
  id:    Page
  label: string
  icon:  React.ComponentType<{ className?: string }>
}

const NAV_ITEMS: NavItem[] = [
  { id: 'overview',     label: 'Overview',     icon: LayoutDashboard },
  { id: 'incidents',    label: 'Incidents',    icon: AlertTriangle },
  { id: 'remediations', label: 'Remediations', icon: Wrench },
  { id: 'llm',          label: 'LLM Analysis', icon: Brain },
  { id: 'outcomes',     label: 'Outcomes',     icon: BarChart3 },
  { id: 'melt',         label: 'MELT',         icon: Layers },
]

interface Props {
  active:        Page
  onNavigate:    (page: Page) => void
  criticalCount: number
  warningCount:  number
}

export default function Navbar({ active, onNavigate, criticalCount, warningCount }: Props) {
  const now = useLiveClock()
  const timeStr = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  const dateStr = now.toLocaleDateString([], { month: 'short', day: 'numeric' })

  return (
    <header className="sticky top-0 z-50 bg-surface-800 border-b border-surface-700">
      <div className="flex items-center gap-4 px-4 md:px-6 h-14">

        {/* Brand */}
        <div className="flex items-center gap-2 flex-shrink-0">
          <ShieldAlert className="w-5 h-5 text-blue-400" />
          <span className="font-bold text-slate-100 text-sm hidden sm:block whitespace-nowrap">
            AIOps Centre
          </span>
        </div>

        {/* Divider */}
        <div className="w-px h-6 bg-surface-600 hidden sm:block" />

        {/* Nav tabs */}
        <nav className="flex items-center gap-0.5 flex-1">
          {NAV_ITEMS.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              onClick={() => onNavigate(id)}
              className={clsx(
                'flex items-center gap-1.5 px-3 py-1.5 rounded text-sm font-medium transition-colors whitespace-nowrap',
                active === id
                  ? 'bg-blue-600 text-white'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-surface-700',
              )}
            >
              <Icon className="w-3.5 h-3.5 flex-shrink-0" />
              <span className="hidden md:inline">{label}</span>
            </button>
          ))}
        </nav>

        {/* Live status badges */}
        <div className="flex items-center gap-3 text-xs flex-shrink-0">
          {criticalCount > 0 && (
            <span className="flex items-center gap-1.5 text-red-400 font-semibold animate-pulse">
              <span className="w-2 h-2 rounded-full bg-red-400" />
              {criticalCount} CRITICAL
            </span>
          )}
          {warningCount > 0 && (
            <span className="flex items-center gap-1.5 text-yellow-400 font-medium">
              <span className="w-2 h-2 rounded-full bg-yellow-400" />
              {warningCount} WARNING
            </span>
          )}
          {criticalCount === 0 && warningCount === 0 && (
            <span className="flex items-center gap-1.5 text-green-400">
              <span className="w-2 h-2 rounded-full bg-green-400" />
              All OK
            </span>
          )}

          {/* Live clock */}
          <div className="hidden sm:flex items-center gap-1.5 text-slate-400 border-l border-surface-600 pl-3 ml-1 font-mono">
            <Clock className="w-3 h-3 text-slate-500" />
            <span>{dateStr}</span>
            <span className="text-slate-200 tabular-nums">{timeStr}</span>
          </div>
        </div>

      </div>
    </header>
  )
}

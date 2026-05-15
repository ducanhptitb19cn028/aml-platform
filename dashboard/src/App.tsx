import { useState, useCallback } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api/client'
import type { Incident, RemediationRecord } from './types'
import { useEventStream } from './hooks/useEventStream'
import Navbar, { type Page } from './components/Navbar'
import OverviewPage     from './pages/OverviewPage'
import IncidentsPage    from './pages/IncidentsPage'
import RemediationsPage from './pages/RemediationsPage'
import LlmPage          from './pages/LlmPage'
import OutcomesPage     from './pages/OutcomesPage'
import MeltPage         from './pages/MeltPage'

export default function App() {
  const [page, setPage] = useState<Page>('overview')

  const [liveIncidents,    setLiveIncidents]    = useState<Incident[]>([])
  const [liveRemediations, setLiveRemediations] = useState<RemediationRecord[]>([])

  const queryClient = useQueryClient()

  const { data: health = [] } = useQuery({
    queryKey:        ['health'],
    queryFn:         api.serviceHealth,
    refetchInterval: 5_000,
  })

  const handleIncident = useCallback((data: unknown) => {
    const inc = data as Incident
    setLiveIncidents(prev =>
      [inc, ...prev.filter(i => i.incidentId !== inc.incidentId)].slice(0, 100),
    )
    queryClient.invalidateQueries({ queryKey: ['health'] })
  }, [queryClient])

  const handleRemediation = useCallback((data: unknown) => {
    const rec = data as RemediationRecord
    setLiveRemediations(prev =>
      [rec, ...prev.filter(r => r.recordId !== rec.recordId)].slice(0, 50),
    )
  }, [])

  useEventStream('/api/v1/stream', {
    incident:    handleIncident,
    remediation: handleRemediation,
  })

  const criticalCount = health.filter(h => h.status === 'CRITICAL').length
  const warningCount  = health.filter(h => h.status === 'WARNING').length

  return (
    <div className="min-h-screen bg-surface-900 text-slate-100">
      <Navbar
        active={page}
        onNavigate={setPage}
        criticalCount={criticalCount}
        warningCount={warningCount}
      />
      <main className="max-w-screen-2xl mx-auto px-4 md:px-6 py-6">
        {page === 'overview'     && (
          <OverviewPage
            health={health}
            liveIncidents={liveIncidents}
            liveRemediations={liveRemediations}
          />
        )}
        {page === 'incidents'    && <IncidentsPage    liveIncidents={liveIncidents} />}
        {page === 'remediations' && <RemediationsPage liveRemediations={liveRemediations} />}
        {page === 'llm'          && <LlmPage />}
        {page === 'outcomes'     && <OutcomesPage />}
        {page === 'melt'         && <MeltPage liveIncidents={liveIncidents} liveRemediations={liveRemediations} />}
      </main>
    </div>
  )
}

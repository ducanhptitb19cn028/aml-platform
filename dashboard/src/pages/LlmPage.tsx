import { useQuery } from '@tanstack/react-query'
import { formatDistanceToNow } from 'date-fns'
import { Brain, Cpu, TrendingDown, AlertTriangle, CheckCircle, RefreshCw } from 'lucide-react'
import clsx from 'clsx'
import type { LlmAnalysis } from '../types'

interface TrainingStatus {
  status:          string
  startedAt:       string | null
  completedAt:     string | null
  trainingSamples: number
  trainLoss:       number | null
  error:           string | null
  adapterReady:    boolean
  lossHistory:     number[]
  minExamples:     number
}

interface Stats {
  trainingExamples: number
  totalAnalyses:    number
  modelId:          string
  adapterReady:     boolean
  ollamaUrl:        string
}

const RISK_COLORS: Record<string, string> = {
  LOW:      'text-green-400  bg-green-900/30  border-green-800',
  MEDIUM:   'text-yellow-400 bg-yellow-900/30 border-yellow-800',
  HIGH:     'text-orange-400 bg-orange-900/30 border-orange-800',
  CRITICAL: 'text-red-400    bg-red-900/30    border-red-800',
}

async function get<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`${res.status}`)
  return res.json()
}

function TrainingPanel({ training, stats }: { training?: TrainingStatus; stats?: Stats }) {
  const isRunning  = training?.status === 'RUNNING'
  const isComplete = training?.status === 'COMPLETED'
  const isFailed   = training?.status === 'FAILED'
  const lastLoss   = training?.lossHistory?.at(-1)
  const max        = training?.lossHistory ? Math.max(...training.lossHistory) : 1

  return (
    <div className="bg-surface-800 rounded-lg border border-surface-700 p-4 space-y-4">
      <h2 className="text-xs font-semibold text-slate-400 uppercase tracking-wider">LoRA Training Status</h2>

      {/* Status badge */}
      <div className="flex flex-wrap items-center gap-3">
        <span className={clsx(
          'flex items-center gap-1.5 px-2.5 py-1 rounded border text-xs font-semibold',
          isRunning  ? 'text-blue-400   bg-blue-900/30   border-blue-800 animate-pulse' :
          isComplete ? 'text-green-400  bg-green-900/30  border-green-800' :
          isFailed   ? 'text-red-400    bg-red-900/30    border-red-800' :
                       'text-slate-400  bg-surface-700   border-surface-600',
        )}>
          {isRunning  && <TrendingDown className="w-3.5 h-3.5" />}
          {isComplete && <CheckCircle  className="w-3.5 h-3.5" />}
          {isFailed   && <AlertTriangle className="w-3.5 h-3.5" />}
          {training?.status ?? 'IDLE'}
        </span>

        {training?.adapterReady && (
          <span className="flex items-center gap-1 text-green-400 text-xs">
            <Cpu className="w-3.5 h-3.5" /> LoRA adapter active
          </span>
        )}
      </div>

      {/* Key metrics */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
        {[
          { label: 'Model',    value: stats?.modelId ?? '—' },
          { label: 'Examples', value: stats?.trainingExamples ?? 0 },
          { label: 'Samples',  value: training?.trainingSamples ?? 0 },
          { label: 'Loss',     value: lastLoss != null ? lastLoss.toFixed(4) : '—' },
        ].map(({ label, value }) => (
          <div key={label} className="bg-surface-700 rounded p-2">
            <p className="text-slate-500">{label}</p>
            <p className="text-slate-200 font-mono font-medium mt-0.5 truncate">{value}</p>
          </div>
        ))}
      </div>

      {/* Loss history sparkline */}
      {(training?.lossHistory?.length ?? 0) > 0 && (
        <div>
          <p className="text-xs text-slate-500 mb-1.5">Loss history ({training!.lossHistory.length} steps)</p>
          <div className="flex items-end gap-0.5 h-12 bg-surface-700 rounded p-1">
            {training!.lossHistory.map((v, i) => {
              const pct = max > 0 ? (v / max) * 100 : 50
              return (
                <div
                  key={i}
                  title={`step ${i + 1}: ${v.toFixed(4)}`}
                  className="flex-1 bg-blue-500 hover:bg-blue-400 rounded-sm min-w-[4px] transition-colors"
                  style={{ height: `${pct}%` }}
                />
              )
            })}
          </div>
        </div>
      )}

      {isFailed && training?.error && (
        <div className="rounded border border-red-800 bg-red-900/20 px-3 py-2 text-xs text-red-400">
          {training.error}
        </div>
      )}
    </div>
  )
}

export default function LlmPage() {
  const { data: analyses = [], isFetching: aFetching, isError } = useQuery<LlmAnalysis[]>({
    queryKey:        ['llmAnalyses'],
    queryFn:         () => get('/api/llm/v1/analyses?limit=20'),
    refetchInterval: 10_000,
    retry:           1,
  })

  const { data: training } = useQuery<TrainingStatus>({
    queryKey:        ['trainStatus'],
    queryFn:         () => get('/api/llm/v1/train/status'),
    refetchInterval: 5_000,
    retry:           1,
  })

  const { data: stats } = useQuery<Stats>({
    queryKey:        ['llmStats'],
    queryFn:         () => get('/api/llm/v1/stats'),
    refetchInterval: 15_000,
    retry:           1,
  })

  return (
    <div className="space-y-5">

      {/* ── Header ─────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-lg font-bold text-slate-100">LLM Analysis</h1>
          <p className="text-xs text-slate-500 mt-0.5">
            Incident explanations via Qwen2.5:3b (Ollama) · LoRA fine-tuning (PEFT)
          </p>
        </div>
        <RefreshCw className={`w-4 h-4 text-slate-500 ${aFetching ? 'animate-spin text-blue-400' : ''}`} />
      </div>

      {/* ── Training panel ─────────────────────────────────────────── */}
      <TrainingPanel training={training} stats={stats} />

      {/* ── Analysis list ──────────────────────────────────────────── */}
      <div className="bg-surface-800 rounded-lg border border-surface-700 p-4">
        <div className="flex items-center gap-2 mb-4">
          <Brain className="w-4 h-4 text-purple-400" />
          <h2 className="text-xs font-semibold text-slate-400 uppercase tracking-wider">
            Recent Analyses ({analyses.length})
          </h2>
        </div>

        {isError ? (
          <div className="rounded border border-yellow-800 bg-yellow-900/20 px-4 py-3 text-sm text-yellow-400">
            LLM engine unavailable — check that llm-engine pod is Running and port-forward is active on 8001.
          </div>
        ) : analyses.length === 0 ? (
          <p className="text-slate-500 text-sm py-6 text-center">
            No analyses yet — incidents must flow through Kafka → llm-engine to generate explanations.
          </p>
        ) : (
          <div className="space-y-3">
            {analyses.map(a => (
              <div
                key={a.analysisId}
                className="rounded-lg bg-surface-700 border border-surface-600 p-4"
              >
                <div className="flex items-start justify-between gap-3 mb-2">
                  <span className={clsx(
                    'px-2 py-0.5 rounded border text-xs font-bold uppercase flex-shrink-0',
                    RISK_COLORS[a.amlRisk] ?? 'text-slate-400 bg-surface-600 border-surface-500',
                  )}>
                    {a.amlRisk}
                  </span>
                  <div className="flex items-center gap-3 text-xs text-slate-500 flex-shrink-0">
                    {a.cacheHit && (
                      <span className="flex items-center gap-1 text-green-400">
                        <Cpu className="w-3 h-3" /> LoRA
                      </span>
                    )}
                    <span className="font-mono">{a.model}</span>
                    <span>{formatDistanceToNow(new Date(a.analyzedAt), { addSuffix: true })}</span>
                  </div>
                </div>

                <p className="text-slate-200 text-sm leading-relaxed mb-2">{a.explanation}</p>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-1 text-xs text-slate-400">
                  <span><span className="text-slate-500">Root cause: </span>{a.rootCause}</span>
                  <span><span className="text-slate-500">Confidence: </span>{(a.confidence * 100).toFixed(0)}%</span>
                  <span className="sm:col-span-2">
                    <span className="text-slate-500">Recommendation: </span>
                    <span className="text-blue-300">{a.recommendation}</span>
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

    </div>
  )
}

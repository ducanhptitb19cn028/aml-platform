import { useQuery } from '@tanstack/react-query'
import { formatDistanceToNow } from 'date-fns'
import { Brain, Cpu, TrendingDown, AlertTriangle } from 'lucide-react'

interface LlmAnalysis {
  analysisId:     string
  incidentId:     string
  analyzedAt:     string
  model:          string
  explanation:    string
  rootCause:      string
  recommendation: string
  amlRisk:        'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  confidence:     number
  cacheHit:       boolean   // true = fine-tuned LoRA adapter was used
  trainingSize:   number
}

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
  LOW:      'text-green-400 bg-green-900/30',
  MEDIUM:   'text-yellow-400 bg-yellow-900/30',
  HIGH:     'text-orange-400 bg-orange-900/30',
  CRITICAL: 'text-red-400 bg-red-900/30',
}

async function get<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`${res.status}`)
  return res.json()
}

export default function LlmAnalysisPanel() {
  const { data: analyses = [], isError } = useQuery<LlmAnalysis[]>({
    queryKey:        ['llmAnalyses'],
    queryFn:         () => get('/api/llm/v1/analyses?limit=8'),
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

  if (isError) {
    return (
      <p className="text-slate-500 text-sm py-4 text-center">
        LLM Engine unavailable — check pod status
      </p>
    )
  }

  const isTraining    = training?.status === 'RUNNING'
  const adapterReady  = training?.adapterReady ?? false
  const lastLoss      = training?.lossHistory?.at(-1)

  return (
    <div>
      {/* ── Stats bar ── */}
      <div className="flex flex-wrap gap-3 mb-3 text-xs">
        <span className="flex items-center gap-1 text-slate-400">
          <Brain className="w-3 h-3 text-purple-400" />
          <span className="text-slate-300">{stats?.modelId ?? 'qwen2.5:3b'}</span>
        </span>
        <span className="text-slate-500">
          {stats?.trainingExamples ?? 0} examples · {stats?.totalAnalyses ?? 0} analyses
        </span>
        {adapterReady && (
          <span className="flex items-center gap-1 text-green-400">
            <Cpu className="w-3 h-3" /> LoRA adapter active
          </span>
        )}
        {isTraining && (
          <span className="flex items-center gap-1 text-blue-400 animate-pulse">
            <TrendingDown className="w-3 h-3" />
            Training… {lastLoss != null ? `loss ${lastLoss.toFixed(4)}` : ''}
          </span>
        )}
        {training?.status === 'FAILED' && (
          <span className="flex items-center gap-1 text-red-400">
            <AlertTriangle className="w-3 h-3" /> Training failed
          </span>
        )}
      </div>

      {/* ── Loss sparkline ── */}
      {(training?.lossHistory?.length ?? 0) > 0 && (
        <div className="mb-3 p-2 rounded bg-surface-700 border border-surface-600">
          <p className="text-xs text-slate-500 mb-1">Training loss history</p>
          <div className="flex items-end gap-0.5 h-8">
            {training!.lossHistory.map((v, i) => {
              const max = Math.max(...training!.lossHistory)
              const pct = max > 0 ? (v / max) * 100 : 50
              return (
                <div
                  key={i}
                  title={`step ${i + 1}: ${v.toFixed(4)}`}
                  className="flex-1 bg-blue-500 rounded-sm min-w-[3px]"
                  style={{ height: `${pct}%` }}
                />
              )
            })}
          </div>
          <p className="text-xs text-slate-500 mt-1">
            Final loss: {lastLoss?.toFixed(4)} · {training!.trainingSamples} samples
          </p>
        </div>
      )}

      {/* ── Analysis list ── */}
      {analyses.length === 0 ? (
        <p className="text-slate-500 text-sm py-4 text-center">
          No LLM analyses yet — waiting for incidents from Ollama…
        </p>
      ) : (
        <div className="space-y-2 max-h-64 overflow-y-auto pr-1">
          {analyses.map(a => (
            <div
              key={a.analysisId}
              className="rounded bg-surface-700 p-3 border border-surface-600 text-xs"
            >
              <div className="flex items-center justify-between mb-1">
                <span className={`px-1.5 py-0.5 rounded text-xs font-semibold uppercase ${RISK_COLORS[a.amlRisk] ?? 'text-slate-400'}`}>
                  {a.amlRisk}
                </span>
                <div className="flex items-center gap-2 text-slate-500">
                  {a.cacheHit && <span className="text-green-400">● LoRA</span>}
                  <span>{formatDistanceToNow(new Date(a.analyzedAt), { addSuffix: true })}</span>
                </div>
              </div>
              <p className="text-slate-300 mb-1 leading-relaxed">{a.explanation}</p>
              <div className="flex flex-wrap gap-x-4 text-slate-400 mb-1">
                <span><span className="text-slate-500">Root cause: </span>{a.rootCause}</span>
                <span><span className="text-slate-500">Confidence: </span>{(a.confidence * 100).toFixed(0)}%</span>
              </div>
              <p className="text-blue-300">
                <span className="text-slate-500">Rec: </span>{a.recommendation}
              </p>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

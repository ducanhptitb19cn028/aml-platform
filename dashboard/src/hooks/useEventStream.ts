import { useEffect, useRef, useCallback } from 'react'

type Handler = (data: unknown) => void

export function useEventStream(
  url: string,
  handlers: Record<string, Handler>,
  enabled = true,
) {
  const esRef       = useRef<EventSource | null>(null)
  const handlersRef = useRef(handlers)
  handlersRef.current = handlers

  const connect = useCallback(() => {
    const es = new EventSource(url)
    esRef.current = es

    Object.keys(handlersRef.current).forEach(eventType => {
      es.addEventListener(eventType, (e: MessageEvent) => {
        try { handlersRef.current[eventType]?.(JSON.parse(e.data)) } catch {}
      })
    })

    es.onerror = () => {
      es.close()
      setTimeout(connect, 3000) // reconnect after 3 s
    }
  }, [url])

  useEffect(() => {
    if (!enabled) return
    connect()
    return () => { esRef.current?.close() }
  }, [connect, enabled])
}

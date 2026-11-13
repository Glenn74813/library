import { useCallback, useEffect, useState } from 'react'
import { errorText } from './api'

/** Loads data when the screen opens; reload() for pull-to-refresh and after changes. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | undefined>()
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(load, deps)
  const reload = useCallback(async () => {
    setLoading(true)
    try {
      setData(await run())
      setError(null)
    } catch (e) {
      setError(errorText(e))
    } finally {
      setLoading(false)
    }
  }, [run])
  useEffect(() => { void reload() }, [reload])
  return { data, error, loading, reload }
}

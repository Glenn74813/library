import { api, ApiError } from './client'

/**
 * Desk transactions recorded while the server can't be reached. They are kept in this browser
 * (so the circulation page must stay on the same PC) and uploaded with their original times.
 */
export interface OfflineTx { id: string; type: 'CHECKOUT' | 'CHECKIN'; patron?: string; item: string; at: string }
export interface SyncResult { id: string; type: 'CHECKOUT' | 'CHECKIN'; item: string; ok: boolean; message: string; warnings: string[] }

const QUEUE_KEY = 'smartlib.circulation.offlineQueue'
const FAILED_KEY = 'smartlib.circulation.offlineFailed'

function read<T>(key: string): T[] {
  try { return JSON.parse(localStorage.getItem(key) ?? '[]') } catch { return [] }
}
function write(key: string, value: unknown[]) {
  try { localStorage.setItem(key, JSON.stringify(value)) } catch { /* storage full or blocked */ }
}

export const offlineQueue = () => read<OfflineTx>(QUEUE_KEY)
export const failedSyncs = () => read<SyncResult & { at?: string; patron?: string }>(FAILED_KEY)
export const clearFailed = () => write(FAILED_KEY, [])

/** True when the server (not the request) is the problem: no network, or the proxy can't reach the API. */
export function isOffline(e: unknown) {
  return !(e instanceof ApiError) || e.status === 502 || e.status === 503 || e.status === 504
}

export function enqueue(tx: Omit<OfflineTx, 'id' | 'at'>): number {
  const q = [...offlineQueue(), { ...tx, id: crypto.randomUUID?.() ?? `${Date.now()}-${Math.random()}`, at: new Date().toISOString() }]
  write(QUEUE_KEY, q)
  return q.length
}

let syncing: Promise<SyncResult[]> | null = null

/** Uploads the queue; results that failed are kept aside for staff to resolve. Throws if still offline. */
export function syncOffline(): Promise<SyncResult[]> {
  syncing ??= (async () => {
    try {
      const q = offlineQueue()
      if (q.length === 0) return []
      const results = await api<SyncResult[]>('/api/circulation/offline-sync', { body: { transactions: q }, timeoutMs: 60_000 })
      const done = new Set(results.map((r) => r.id))
      write(QUEUE_KEY, offlineQueue().filter((t) => !done.has(t.id)))
      const byId = new Map(q.map((t) => [t.id, t]))
      const failed = results.filter((r) => !r.ok).map((r) => ({ ...r, at: byId.get(r.id)?.at, patron: byId.get(r.id)?.patron }))
      if (failed.length) write(FAILED_KEY, [...failedSyncs(), ...failed])
      return results
    } finally {
      syncing = null
    }
  })()
  return syncing
}

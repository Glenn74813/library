import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, api } from '../../api/client'
import { isOffline } from '../../api/offlineCirculation'
import type { EntryMethod, KioskConfig, TapResult } from '../../api/types'
import { useKioskConnected } from '../../auth/AuthContext'
import { KioskResetZone, KioskSetup } from '../../components/KioskSetup'
import { BroadcastOverlay } from '../../components/BroadcastOverlay'
import { applyKey, OnScreenKeyboard } from '../../components/OnScreenKeyboard'
import { Icon } from '../../components/Icon'
import { Logo } from '../../components/Logo'
import { useLibrary } from '../../api/library'

const QUEUE_KEY = 'smartlib.attendance.offlineQueue'
/** How long a tap result (name, photo, time in/out) stays on screen before the kiosk is ready for the next person. */
const RESULT_MS = 5000
/** A tap that gets no answer within this long is queued like an offline one. */
const TAP_TIMEOUT = 6000

interface QueuedTap { id?: string; code: string; method: EntryMethod; timestamp: string }

const newId = () => crypto.randomUUID?.() ?? `${Date.now()}-${Math.random()}`

function readQueue(): QueuedTap[] {
  try { return JSON.parse(localStorage.getItem(QUEUE_KEY) ?? '[]') } catch { return [] }
}
function writeQueue(q: QueuedTap[]) {
  try { localStorage.setItem(QUEUE_KEY, JSON.stringify(q)) } catch { /* storage full/blocked */ }
}

type Shown =
  | { kind: 'ok'; result: TapResult }
  | { kind: 'offline'; code: string }
  | { kind: 'error'; message: string }

/**
 * Library attendance kiosk (time-in / time-out). RFID card readers and barcode scanners act as
 * keyboards, so input is captured by an always-focused field terminated by Enter. Taps made while
 * the server is unreachable are queued locally and synced automatically.
 */
export function AttendanceKiosk() {
  const connected = useKioskConnected()
  if (!connected) return <KioskSetup title="Attendance kiosk setup" />
  return <KioskScreen />
}


function KioskScreen() {
  const lib = useLibrary()
  const [config, setConfig] = useState<KioskConfig | null>(null)
  const [online, setOnline] = useState(true)
  const [queued, setQueued] = useState(readQueue().length)
  const [shown, setShown] = useState<Shown | null>(null)
  const [manual, setManual] = useState(false)
  const [manualCode, setManualCode] = useState('')
  const [now, setNow] = useState(new Date())
  const [msgIndex, setMsgIndex] = useState(0)
  const scanRef = useRef<HTMLInputElement>(null)
  const buffer = useRef('')
  const hideTimer = useRef<number>(0)
  const syncing = useRef(false)

  const loadConfig = useCallback(async () => {
    try {
      setConfig(await api<KioskConfig>('/api/kiosk/config', { slot: 'kiosk' }))
      setOnline(true)
    } catch (e) {
      if (isOffline(e)) setOnline(false)
    }
  }, [])

  const syncQueue = useCallback(async () => {
    if (syncing.current) return
    // Give older entries an id so only the uploaded ones are removed afterwards.
    const q = readQueue().map((t) => (t.id ? t : { ...t, id: newId() }))
    if (!q.length) return
    writeQueue(q)
    syncing.current = true
    try {
      await api('/api/kiosk/attendance/sync', { slot: 'kiosk', body: { taps: q.map(({ code, method, timestamp }) => ({ code, method, timestamp })) } })
      // Taps queued during the upload stay for the next sync.
      const sent = new Set(q.map((t) => t.id))
      const rest = readQueue().filter((t) => !t.id || !sent.has(t.id))
      writeQueue(rest)
      setQueued(rest.length)
      setOnline(true)
    } catch (e) {
      if (isOffline(e)) setOnline(false)
    } finally {
      syncing.current = false
    }
  }, [])

  useEffect(() => {
    void loadConfig(); void syncQueue()
    const c = setInterval(loadConfig, 20_000)
    const s = setInterval(syncQueue, 30_000)
    const t = setInterval(() => setNow(new Date()), 1000)
    window.addEventListener('online', syncQueue)
    return () => { clearInterval(c); clearInterval(s); clearInterval(t); window.removeEventListener('online', syncQueue) }
  }, [loadConfig, syncQueue])

  useEffect(() => {
    const m = setInterval(() => setMsgIndex((i) => i + 1), 8000)
    return () => clearInterval(m)
  }, [])

  // Keep the hidden scan field focused so card readers always land in it.
  useEffect(() => {
    if (manual) return
    const refocus = () => scanRef.current?.focus()
    refocus()
    const i = setInterval(refocus, 1500)
    return () => clearInterval(i)
  }, [manual])

  const show = (s: Shown) => {
    setShown(s)
    window.clearTimeout(hideTimer.current)
    hideTimer.current = window.setTimeout(() => setShown(null), RESULT_MS)
  }

  async function tap(code: string, method: EntryMethod) {
    const c = code.trim()
    if (!c) return
    try {
      const result = await api<TapResult>('/api/kiosk/attendance/tap', { slot: 'kiosk', body: { code: c, method }, timeoutMs: TAP_TIMEOUT })
      setOnline(true)
      show({ kind: 'ok', result })
    } catch (e) {
      if (e instanceof ApiError && !isOffline(e)) {
        show({ kind: 'error', message: e.status === 404 ? 'ID not recognized. Please see the librarian.' : e.message })
      } else {
        const q = [...readQueue(), { id: newId(), code: c, method, timestamp: new Date().toISOString() }]
        writeQueue(q)
        setQueued(q.length)
        setOnline(false)
        show({ kind: 'offline', code: c })
      }
    }
  }

  function onScan(e: FormEvent) {
    e.preventDefault()
    const code = buffer.current
    buffer.current = ''
    if (scanRef.current) scanRef.current.value = ''
    // Readers that emit hex UIDs are treated as RFID; everything else as barcode.
    void tap(code, /^[0-9A-F]{8,}$/i.test(code) && !/^\d+$/.test(code) ? 'RFID' : 'BARCODE')
  }

  const standby = config?.mode === 'STANDBY'
  const messages = config?.standbyMessages ?? []

  return (
    <div className="att">
      <BroadcastOverlay />
      <KioskResetZone />
      <header>
        <div className="school">
          <Logo large />
          <div>
            <div className="name">{lib.name}</div>
            <div style={{ opacity: 0.8 }}>{standby ? 'Information board' : 'Library attendance'}</div>
          </div>
        </div>
        <div className="clock">
          <div className="time">{now.toLocaleTimeString('en-PH', { hour: 'numeric', minute: '2-digit' })}</div>
          <div>{now.toLocaleDateString('en-PH', { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' })}</div>
        </div>
      </header>

      <form onSubmit={onScan} style={{ position: 'absolute', opacity: 0, pointerEvents: 'none' }} aria-hidden>
        <input ref={scanRef} autoComplete="off" inputMode="none" tabIndex={-1}
          onChange={(e) => { buffer.current = e.target.value }} />
      </form>

      <main>
        {shown ? <ResultCard shown={shown} />
          : manual ? (
            <div className="manual">
              <h2 style={{ marginTop: 0 }}>Enter your ID number</h2>
              <input value={manualCode} readOnly aria-label="ID number" />
              <OnScreenKeyboard onKey={(k) => setManualCode((v) => applyKey(v, k).toUpperCase())} enterLabel="Submit"
                onEnter={() => { void tap(manualCode, 'MANUAL'); setManualCode(''); setManual(false) }} />
              <button className="btn block" style={{ marginTop: 12 }} onClick={() => { setManual(false); setManualCode('') }}>Cancel</button>
            </div>
          ) : standby ? (
            <div className="stack" style={{ justifyItems: 'center' }}>
              {config?.videoUrl && <video src={config.videoUrl} autoPlay muted loop playsInline />}
              {messages.length > 0 && <div className="ticker">{messages[msgIndex % messages.length]}</div>}
            </div>
          ) : (
            <div>
              <div className="tap-icon"><Icon name="credit-card" size="1em" /></div>
              <p className="prompt">Tap or scan your ID</p>
              <p className="sub">{config?.singleTap ? 'to log your library visit' : 'to time in or time out'}</p>
              {messages.length > 0 && <p className="sub" style={{ marginTop: '2em' }}>{messages[msgIndex % messages.length]}</p>}
            </div>
          )}
      </main>

      <footer>
        <div className="row">
          {!online && <span className="offline-pill">OFFLINE — taps saved{queued ? ` (${queued})` : ''}</span>}
          {online && queued > 0 && <span className="offline-pill">Syncing {queued}…</span>}
        </div>
        {!standby && !manual && <button className="btn ghost" onClick={() => setManual(true)}><Icon name="type" />No ID with you? Enter your ID number</button>}
      </footer>
    </div>
  )
}

function ResultCard({ shown }: { shown: Shown }) {
  if (shown.kind === 'error') {
    return <div className="result-card err"><div className="action" style={{ color: 'var(--brand-red)' }}>NOT RECORDED</div>
      <div className="who" style={{ fontSize: '1.6em' }}>{shown.message}</div></div>
  }
  if (shown.kind === 'offline') {
    return <div className="result-card in"><div className="action" style={{ color: 'var(--warning)' }}>SAVED OFFLINE</div>
      <div className="who">{shown.code}</div><div>Your visit will be recorded when the connection returns.</div></div>
  }
  const r = shown.result
  const isIn = r.action === 'IN'
  return (
    <div className={`result-card ${isIn ? 'in' : 'out'}`}>
      <div className="row" style={{ justifyContent: 'center', gap: '4vmin', flexWrap: 'wrap' }}>
        {r.photoUrl && <img src={r.photoUrl} alt="" />}
        <div>
          <div className="action" style={{ color: isIn ? 'var(--brand-green)' : 'var(--brand-blue)' }}>{isIn ? 'TIME IN' : 'TIME OUT'} · {new Date(r.time).toLocaleTimeString('en-PH', { hour: 'numeric', minute: '2-digit' })}</div>
          <div className="who">{r.patronName}</div>
          <div>{[r.course, r.category].filter(Boolean).join(' · ')}</div>
        </div>
      </div>
      <p style={{ fontSize: '1.2em', marginBottom: 0 }}>{r.message}</p>
      {r.warnings.map((w) => <p key={w} style={{ color: 'var(--brand-red)', fontWeight: 700, marginBottom: 0 }}><Icon name="alert-triangle" /> {w}</p>)}
    </div>
  )
}

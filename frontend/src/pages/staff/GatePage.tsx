import { useEffect, useRef, useState } from 'react'
import { api, fetchBytes, qs } from '../../api/client'
import type { GateConfig, GateEvent, GateEventType, GateSummary, Page, VoiceSettings } from '../../api/types'
import { loadVoices, pickVoice, speak, speechSupported } from '../../speech'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Pager, Stat, StatusBadge, Table, Tabs, ExportMenu } from '../../components/ui'
import { daysAgo, fmtDateTime, isoDate, label } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'
import { useLibrary } from '../../api/library'

/** Announcement preset; a recording (MP3...) is played on kiosks instead of text-to-speech. */
interface Preset { id?: string; name: string; message: string; hasAudio?: boolean; audioFilename?: string | null }

const TYPES: GateEventType[] = ['ALARM', 'TAMPER', 'POWER_LOSS', 'POWER_RESTORED', 'VISITOR', 'EMERGENCY_ON', 'EMERGENCY_OFF', 'BROADCAST']

export function GatePage() {
  const canEdit = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  const [tab, setTab] = useState<'control' | 'voice' | 'events'>('control')
  const [type, setType] = useState('')
  const [from, setFrom] = useState(daysAgo(6))
  const [to, setTo] = useState(isoDate())
  const [page, setPage] = useState(0)
  const [message, setMessage] = useState('')
  const toast = useToast()
  const { data: summary, reload: reloadSummary } = useAsync(() => api<GateSummary>('/api/gate/summary'), [])
  const { data: events, error, reload } = useAsync(
    () => api<Page<GateEvent>>(`/api/gate/events${qs({ type, from, to, page })}`), [type, from, to, page])

  async function setMode(emergencyMode: boolean, alarmEnabled: boolean) {
    if (emergencyMode && !summary?.emergencyMode && !confirm('Turn ON emergency/evacuation mode? Gate alarms will be disabled.')) return
    try {
      await api('/api/gate/settings', { method: 'PUT', body: { emergencyMode, alarmEnabled } })
      toast('Gate settings updated')
      void reloadSummary(); void reload()
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  const { data: presets, setData: setPresets, reload: reloadPresets } = useAsync(() => api<Preset[]>('/api/gate/presets'), [])
  const [managing, setManaging] = useState(false)

  async function broadcast(text: string, presetId?: string) {
    if (!text.trim()) return
    try {
      await api('/api/gate/broadcast', { body: { message: text, presetId } })
      toast('Announcement sent to library displays')
      setMessage('')
      void reload()
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  return (
    <>
      <div className="page-head">
        <h1>Security gate</h1>
        <div className="row">
          <ExportMenu path={`/api/gate/export${qs({ from, to })}`} name="gate-events" label="Alarm / event log…" />
          <ExportMenu path={`/api/reports/export/loans${qs({ from, to })}`} name="circulation" label="Circulation report…" />
          <ExportMenu path="/api/reports/export/tagging" name="tagging" label="Tagging report…" />
        </div>
      </div>
      {summary?.emergencyMode && <div className="alert error" style={{ marginBottom: 16 }}><strong>EMERGENCY MODE IS ON</strong> — gate alarms are suppressed and the gate never blocks people.</div>}
      {summary && (
        <div className="stats" style={{ marginBottom: 16 }}>
          <Stat label="Alarms today" value={summary.alarmsToday} tone={summary.alarmsToday ? 'bad' : undefined} />
          <Stat label="Visitors today" value={summary.visitorsToday} />
          <Stat label="Tamper events" value={summary.tamperToday} tone={summary.tamperToday ? 'bad' : undefined} />
          <Stat label="Power-loss events" value={summary.powerLossToday} tone={summary.powerLossToday ? 'warn' : undefined} />
        </div>
      )}
      <Tabs value={tab} onChange={setTab} tabs={[
        { id: 'control', label: 'Gate control & broadcast' },
        { id: 'voice', label: 'Announcement voice' },
        { id: 'events', label: 'Event log' },
      ]} />
      {tab === 'control' && <div className="grid cols-2" style={{ marginBottom: 16 }}>
        <div className="card stack">
          <h2>Gate control & device health</h2>
          {summary && <AlarmSchedule config={summary.config} canEdit={canEdit} onSaved={() => void reloadSummary()} />}
          {summary?.gates.length ? summary.gates.map((g) => (
            <div key={g.gateId} className="row"><strong>{g.gateId}</strong><span className={`badge ${g.online ? 'ok' : 'bad'}`}>{g.online ? 'Online' : 'Offline'}</span>
              <span className="muted small">last seen {fmtDateTime(g.lastSeen)}</span></div>
          )) : <p className="muted small">No gate has reported yet.</p>}
          {summary && canEdit && (
            <div className="row actions-stretch">
              <button className={`btn ${summary.emergencyMode ? '' : 'danger solid'}`} onClick={() => setMode(!summary.emergencyMode, summary.alarmEnabled)}>
                <Icon name="alert-octagon" />{summary.emergencyMode ? 'End emergency mode' : 'Emergency / evacuation mode'}</button>
              <button className="btn" onClick={() => setMode(summary.emergencyMode, !summary.alarmEnabled)}>
                {summary.alarmEnabled ? 'Disable alarm' : 'Enable alarm'}</button>
            </div>
          )}
        </div>
        <div className="card stack">
          <h2>Announcement broadcast</h2>
          <p className="muted small">Shows on every library kiosk/display and is read aloud (text-to-speech).</p>
          <textarea value={message} onChange={(e) => setMessage(e.target.value)} placeholder="e.g. Earthquake drill: please proceed calmly to the nearest exit." />
          <div className="row end actions-stretch">
            <button className="btn primary" onClick={() => broadcast(message)}><Icon name="volume-2" />Broadcast</button>
          </div>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <h3 style={{ margin: 0 }}>Presets</h3>
            {canEdit && <button className="btn small" onClick={() => setManaging(true)}><Icon name="settings" />Manage presets</button>}
          </div>
          {presets?.length ? (
            <div className="row actions-stretch">
              {presets.map((p) => (
                <button key={p.name} className="btn" title={p.message}
                  onClick={() => confirm(`Broadcast "${p.name}" to all library displays?\n\n${p.message}`) && broadcast(p.message, p.id)}>
                  <Icon name={p.hasAudio ? 'music' : 'volume-2'} />{p.name}</button>
              ))}
            </div>
          ) : <p className="muted small" style={{ margin: 0 }}>No presets yet.</p>}
        </div>
      </div>}
      {tab === 'voice' && <VoiceCard canEdit={canEdit} />}
      {managing && presets && <PresetsModal value={presets} onClose={() => { setManaging(false); void reloadPresets() }}
        onSaved={(p) => { setPresets(p); setManaging(false) }} />}
      {tab === 'events' && <div className="card">
        <h2>Event log</h2>
        <div className="form-grid" style={{ marginBottom: 12 }}>
          <Field label="Event"><select value={type} onChange={(e) => { setType(e.target.value); setPage(0) }}>
            <option value="">All events</option>{TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}</select></Field>
          <Field label="From"><input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label="To"><input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
        </div>
        <ErrorAlert error={error} />
        <Table rows={events?.content} rowKey={(e) => e.id} empty="No events" columns={[
          { header: 'Time', render: (e) => fmtDateTime(e.occurredAt) },
          { header: 'Gate', render: (e) => e.gateId },
          { header: 'Event', render: (e) => <StatusBadge status={e.type} /> },
          { header: 'Item', render: (e) => e.title ? <>{e.title}<div className="muted small mono">{e.barcode}</div></> : e.epc ?? '—' },
          { header: 'Details', render: (e) => e.visitorCount != null ? `${e.visitorCount} visitor(s)` : e.message ?? '—' },
        ]} />
        <Pager page={events} onPage={setPage} />
      </div>}
    </>
  )
}

/** Edit the announcement presets shown as buttons on this page (librarians and admins). */
function PresetsModal({ value, onClose, onSaved }: { value: Preset[]; onClose: () => void; onSaved: (p: Preset[]) => void }) {
  const [list, setList] = useState<Preset[]>(value.length ? value : [{ name: '', message: '' }])
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  const set = (i: number, k: keyof Preset, v: string) => setList((l) => l.map((p, j) => (j === i ? { ...p, [k]: v } : p)))
  const move = (i: number, d: number) => setList((l) => {
    const j = i + d
    if (j < 0 || j >= l.length) return l
    const c = [...l];
    [c[i], c[j]] = [c[j], c[i]]
    return c
  })

  async function save() {
    setError(null)
    const cleaned = list.filter((p) => p.name.trim() || p.message.trim())
    if (cleaned.some((p) => !p.name.trim() || !p.message.trim())) return setError('Each preset needs a name and a message.')
    if (new Set(cleaned.map((p) => p.name.trim().toLowerCase())).size !== cleaned.length) return setError('Preset names must be different.')
    try {
      onSaved(await api<Preset[]>('/api/gate/presets', { method: 'PUT', body: cleaned }))
      toast('Presets saved')
    } catch (e) {
      setError(errorText(e))
    }
  }

  return (
    <Modal title="Announcement presets" onClose={onClose} footer={<>
      <button className="btn" disabled={list.length >= 12} onClick={() => setList((l) => [...l, { name: '', message: '' }])}><Icon name="plus" />Add preset</button>
      <span className="spacer" />
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn primary" onClick={save}>Save presets</button>
    </>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <p className="muted small" style={{ margin: 0 }}>Each preset becomes a button on the Security gate page. The message is shown on every
          kiosk/display and read aloud. Attach a recording (e.g. read in Filipino by a staff member) to play it instead of the
          computer voice. Up to 12 presets.</p>
        {list.map((p, i) => (
          <div key={i} className="card stack" style={{ padding: 12 }}>
            <div className="form-grid">
              <Field label="Button name" hint="e.g. Evacuation, Earthquake drill">
                <input value={p.name} maxLength={40} onChange={(e) => set(i, 'name', e.target.value)} />
              </Field>
              <Field label="Announcement message" className="span-all">
                <textarea value={p.message} maxLength={500} onChange={(e) => set(i, 'message', e.target.value)} />
              </Field>
            </div>
            <Recording preset={p} onChange={(u) => setList((l) => l.map((x, j) => (j === i ? { ...x, hasAudio: u.hasAudio, audioFilename: u.audioFilename } : x)))} />
            <div className="row end">
              <button className="btn small" disabled={i === 0} onClick={() => move(i, -1)} aria-label="Move up">Move up</button>
              <button className="btn small" disabled={i === list.length - 1} onClick={() => move(i, 1)} aria-label="Move down">Move down</button>
              <button className="btn small danger" onClick={() => setList((l) => l.filter((_, j) => j !== i))}><Icon name="trash-2" />Remove</button>
            </div>
          </div>
        ))}
      </div>
    </Modal>
  )
}

const MAX_RECORD_SECONDS = 90

/** Upload, record (microphone), play or remove the recorded audio of one saved preset. */
function Recording({ preset, onChange }: { preset: Preset; onChange: (p: Preset) => void }) {
  const fileRef = useRef<HTMLInputElement>(null)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const recorder = useRef<MediaRecorder | null>(null)
  const timer = useRef<number>(0)
  /** False once unmounted: a microphone that is granted after that is switched straight off. */
  const alive = useRef(true)
  const [busy, setBusy] = useState(false)
  const [playing, setPlaying] = useState(false)
  const [recording, setRecording] = useState(false)
  const [seconds, setSeconds] = useState(0)
  const [take, setTake] = useState<{ blob: Blob; url: string } | null>(null)
  const toast = useToast()
  const canRecord = typeof window !== 'undefined' && 'MediaRecorder' in window && !!navigator.mediaDevices?.getUserMedia

  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
      audioRef.current?.pause()
      window.clearInterval(timer.current)
      recorder.current?.stream.getTracks().forEach((t) => t.stop())
    }
  }, [])

  if (!preset.id) {
    return <p className="muted small" style={{ margin: 0 }}><Icon name="mic" /> Save presets first, then attach a recording to this one.</p>
  }

  async function upload(file: Blob, name: string) {
    if (file.size > 10 * 1024 * 1024) return toast('Recording is too large (limit 10 MB)', 'error')
    const fd = new FormData()
    fd.append('file', file, name)
    setBusy(true)
    try {
      onChange(await api<Preset>(`/api/gate/presets/${preset.id}/audio`, { body: fd }))
      toast('Recording saved to this preset')
      discardTake()
    } catch (e) {
      toast(errorText(e), 'error')
    } finally {
      setBusy(false)
      if (fileRef.current) fileRef.current.value = ''
    }
  }

  async function startRecording() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
      if (!alive.current) { stream.getTracks().forEach((t) => t.stop()); return }
      const type = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4'].find((t) => MediaRecorder.isTypeSupported(t))
      const rec = new MediaRecorder(stream, type ? { mimeType: type } : undefined)
      const chunks: Blob[] = []
      rec.ondataavailable = (e) => e.data.size && chunks.push(e.data)
      rec.onstop = () => {
        stream.getTracks().forEach((t) => t.stop())
        window.clearInterval(timer.current)
        setRecording(false)
        const blob = new Blob(chunks, { type: rec.mimeType || 'audio/webm' })
        if (blob.size) setTake({ blob, url: URL.createObjectURL(blob) })
      }
      recorder.current = rec
      discardTake()
      rec.start()
      setSeconds(0)
      setRecording(true)
      timer.current = window.setInterval(() => setSeconds((s) => {
        if (s + 1 >= MAX_RECORD_SECONDS && rec.state === 'recording') rec.stop()
        return s + 1
      }), 1000)
    } catch (e) {
      const denied = e instanceof DOMException && (e.name === 'NotAllowedError' || e.name === 'SecurityError')
      toast(denied ? 'Microphone access was blocked. Allow the microphone for this site and try again.' : errorText(e), 'error')
    }
  }

  function stopRecording() {
    if (recorder.current?.state === 'recording') recorder.current.stop()
  }

  function discardTake() {
    setTake((t) => { if (t) URL.revokeObjectURL(t.url); return null })
  }

  function playUrl(url: string, revoke: boolean) {
    audioRef.current?.pause()
    const a = new Audio(url)
    audioRef.current = a
    a.onended = () => { setPlaying(false); if (revoke) URL.revokeObjectURL(url) }
    setPlaying(true)
    return a.play()
  }

  async function playSaved() {
    if (playing) { audioRef.current?.pause(); setPlaying(false); return }
    try {
      const { data, contentType } = await fetchBytes(`/api/gate/presets/${preset.id}/audio`)
      await playUrl(URL.createObjectURL(new Blob([data], { type: contentType })), true)
    } catch (e) {
      setPlaying(false)
      toast(errorText(e), 'error')
    }
  }

  async function remove() {
    if (!confirm('Remove this recording? Kiosks will read the message with the computer voice instead.')) return
    try {
      onChange(await api<Preset>(`/api/gate/presets/${preset.id}/audio`, { method: 'DELETE' }))
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  const mmss = (n: number) => `${Math.floor(n / 60)}:${String(n % 60).padStart(2, '0')}`
  const ext = take?.blob.type.includes('mp4') ? 'm4a' : 'webm'

  return (
    <div className="recording">
      <input ref={fileRef} type="file" hidden accept="audio/mpeg,.mp3,audio/wav,.wav,audio/ogg,.ogg,audio/mp4,.m4a,audio/webm,.webm"
        onChange={(e) => e.target.files?.[0] && upload(e.target.files[0], e.target.files[0].name)} />
      <Icon name={preset.hasAudio ? 'music' : 'mic'} className="recording-icon" />

      {recording ? (
        <>
          <span className="recording-name"><span className="rec-dot" aria-hidden /> <strong>Recording…</strong> {mmss(seconds)}
            <span className="muted"> (max {mmss(MAX_RECORD_SECONDS)})</span></span>
          <span className="row"><button className="btn small danger solid" onClick={stopRecording}>Stop</button></span>
        </>
      ) : take ? (
        <>
          <span className="recording-name"><strong>New recording</strong> · {mmss(seconds)} — listen, then save or record again</span>
          <span className="row">
            <button className="btn small" onClick={() => void playUrl(take.url, false)}><Icon name="play" />Play</button>
            <button className="btn small" onClick={startRecording}><Icon name="mic" />Again</button>
            <button className="btn small" onClick={discardTake}>Discard</button>
            <button className="btn small primary" disabled={busy}
              onClick={() => void upload(take.blob, `${preset.name.replace(/[^\w-]+/g, '-').toLowerCase()}-recording.${ext}`)}>
              {busy ? 'Saving…' : 'Save recording'}</button>
          </span>
        </>
      ) : (
        <>
          <span className="recording-name">
            {preset.hasAudio ? <><strong>Recording:</strong> {preset.audioFilename}</> : <span className="muted">No recording — computer voice reads the message</span>}
          </span>
          <span className="row">
            {preset.hasAudio && <button className="btn small" onClick={playSaved}><Icon name="play" />{playing ? 'Stop' : 'Play'}</button>}
            {canRecord && <button className="btn small" onClick={startRecording}><Icon name="mic" />Record</button>}
            <button className="btn small" disabled={busy} onClick={() => fileRef.current?.click()}>
              <Icon name="upload" />{busy ? 'Uploading…' : preset.hasAudio ? 'Replace file' : 'Upload MP3'}</button>
            {preset.hasAudio && <button className="btn small danger" onClick={remove}>Remove</button>}
          </span>
        </>
      )}
    </div>
  )
}

const COMMON_LANGS = [
  { id: 'en-PH', label: 'English (Philippines)' },
  { id: 'fil-PH', label: 'Filipino' },
  { id: 'en-US', label: 'English (US)' },
  { id: 'en-GB', label: 'English (UK)' },
]

/** Philippine voices first, then other English voices, then everything else. */
function voiceRank(v: SpeechSynthesisVoice) {
  const l = v.lang.toLowerCase()
  if (l.endsWith('-ph') || l.startsWith('fil') || l.startsWith('tl')) return 0
  if (l.startsWith('en')) return 1
  return 2
}

/** Voice used to read announcements aloud on every kiosk/display. */
function VoiceCard({ canEdit }: { canEdit: boolean }) {
  const lib = useLibrary()
  // Spell out acronyms such as "ZSCMST" so the voice reads them letter by letter.
  const spokenName = lib.name.replace(/\b[A-Z]{2,}\b/g, (w) => w.split('').join(' '))
  const { data: saved, setData: setSaved } = useAsync(() => api<VoiceSettings>('/api/gate/voice'), [])
  const [v, setV] = useState<VoiceSettings | null>(null)
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>([])
  const toast = useToast()

  useEffect(() => { if (saved) setV(saved) }, [saved])
  useEffect(() => {
    void loadVoices().then((list) => setVoices([...list].sort((a, b) => voiceRank(a) - voiceRank(b) || a.lang.localeCompare(b.lang) || a.name.localeCompare(b.name))))
  }, [])

  if (!v) return null
  const set = <K extends keyof VoiceSettings>(k: K, val: VoiceSettings[K]) => setV({ ...v, [k]: val })
  const groups = voices.reduce<Record<string, SpeechSynthesisVoice[]>>((acc, x) => { (acc[x.lang] ||= []).push(x); return acc }, {})
  const active = pickVoice(voices, v)
  const nameMissing = v.name && !voices.some((x) => x.name === v.name)

  async function save() {
    try {
      setSaved(await api<VoiceSettings>('/api/gate/voice', { method: 'PUT', body: v }))
      toast('Announcement voice saved — kiosks pick it up within a minute')
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  return (
    <div className="card stack" style={{ marginBottom: 16 }}>
      <h2 style={{ margin: 0 }}>Announcement voice</h2>
      {!speechSupported ? <div className="alert warn small">This browser has no text-to-speech. Open this page in Chrome or Edge to choose a voice.</div> : <>
        <fieldset disabled={!canEdit} className="form-grid" style={{ border: 0, padding: 0, margin: 0 }}>
          <Field label="Voice" hint={`${voices.length} voice(s) installed on this computer`} className="span-all">
            <select value={v.name} onChange={(e) => {
              const picked = voices.find((x) => x.name === e.target.value)
              setV({ ...v, name: e.target.value, lang: picked?.lang ?? v.lang })
            }}>
              <option value="">Automatic — best voice for the language below</option>
              {nameMissing && <option value={v.name}>{v.name} (not installed here)</option>}
              {Object.entries(groups).map(([lang, list]) => (
                <optgroup key={lang} label={lang}>
                  {list.map((x) => <option key={x.name} value={x.name}>{x.name}{x.localService ? '' : ' (online)'}</option>)}
                </optgroup>
              ))}
            </select>
          </Field>
          <Field label="Language" hint="Used when the chosen voice isn't on a kiosk">
            <select value={v.lang} onChange={(e) => set('lang', e.target.value)}>
              {[...COMMON_LANGS, ...(COMMON_LANGS.some((l) => l.id === v.lang) ? [] : [{ id: v.lang, label: v.lang }])]
                .map((l) => <option key={l.id} value={l.id}>{l.label}</option>)}
            </select>
          </Field>
          <Field label="Repeat announcement">
            <select value={v.repeat} onChange={(e) => set('repeat', Number(e.target.value))}>
              {[1, 2, 3, 4, 5].map((n) => <option key={n} value={n}>{n === 1 ? 'Once' : `${n} times`}</option>)}
            </select>
          </Field>
          <Field label={`Speed: ${v.rate.toFixed(2)}×`}>
            <input type="range" min={0.5} max={1.5} step={0.05} value={v.rate} onChange={(e) => set('rate', Number(e.target.value))} />
          </Field>
          <Field label={`Pitch: ${v.pitch.toFixed(1)}`}>
            <input type="range" min={0} max={2} step={0.1} value={v.pitch} onChange={(e) => set('pitch', Number(e.target.value))} />
          </Field>
        </fieldset>
        <p className="muted small" style={{ margin: 0 }}>
          {active ? <>Will speak with <strong>{active.name}</strong> ({active.lang}) on this computer. </> : 'No matching voice on this computer; the device default will be used. '}
          Kiosks use the same voice when it is installed on them, otherwise the best voice for the language.
          For Philippine English or Filipino voices, use Microsoft Edge (its online voices) or add the language in Windows/Android speech settings.
        </p>
        <div className="row end actions-stretch">
          <button className="btn" onClick={() => void speak(`Good day. This is a test announcement from the ${spokenName}.`, v, 1)}>
            <Icon name="volume-2" />Test voice</button>
          {canEdit && <button className="btn primary" onClick={save}>Save voice</button>}
        </div>
      </>}
    </div>
  )
}

/** When the gate alarm is armed: always, during opening hours, or custom daily hours. */
function AlarmSchedule({ config, canEdit, onSaved }: { config: GateConfig; canEdit: boolean; onSaved: () => void }) {
  const [c, setC] = useState(config)
  const toast = useToast()
  useEffect(() => setC(config), [config])
  const hhmm = (t: string | null) => (t ? t.slice(0, 5) : '')
  async function save() {
    try {
      await api('/api/gate/settings', { method: 'PUT', body: { emergencyMode: c.emergencyMode, alarmEnabled: c.alarmEnabled,
        schedule: c.schedule, armFrom: c.armFrom || null, armTo: c.armTo || null } })
      toast('Alarm schedule saved')
      onSaved()
    } catch (e) { toast(errorText(e), 'error') }
  }
  return (
    <div className="stack" style={{ gap: 8 }}>
      <div className="row">
        <strong>Alarm</strong>
        {!config.alarmEnabled ? <span className="badge bad">Disabled</span>
          : config.emergencyMode ? <span className="badge bad">Suppressed (emergency)</span>
            : config.armedNow ? <span className="badge ok">Armed now</span> : <span className="badge warn">Off — outside alarm hours</span>}
      </div>
      <fieldset disabled={!canEdit} className="form-grid" style={{ border: 0, padding: 0, margin: 0 }}>
        <Field label="Alarm schedule">
          <select value={c.schedule} onChange={(e) => setC({ ...c, schedule: e.target.value as GateConfig['schedule'] })}>
            <option value="ALWAYS">Always armed</option>
            <option value="OPEN_HOURS">During opening hours (library calendar)</option>
            <option value="CUSTOM">Custom daily hours</option>
          </select>
        </Field>
        {c.schedule === 'CUSTOM' && <>
          <Field label="Armed from"><input type="time" value={hhmm(c.armFrom)} onChange={(e) => setC({ ...c, armFrom: e.target.value })} /></Field>
          <Field label="Armed until"><input type="time" value={hhmm(c.armTo)} onChange={(e) => setC({ ...c, armTo: e.target.value })} /></Field>
        </>}
      </fieldset>
      {canEdit && c.schedule !== config.schedule || canEdit && (c.armFrom !== config.armFrom || c.armTo !== config.armTo)
        ? <div className="row end"><button className="btn small primary" onClick={save}>Save schedule</button></div> : null}
      <p className="muted small" style={{ margin: 0 }}>Outside the schedule, unborrowed items are still logged but the gate does not sound.</p>
    </div>
  )
}

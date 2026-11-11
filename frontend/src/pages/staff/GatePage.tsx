import { useEffect, useState } from 'react'
import { api, download, qs } from '../../api/client'
import type { GateEvent, GateEventType, GateSummary, Page, VoiceSettings } from '../../api/types'
import { loadVoices, pickVoice, speak, speechSupported } from '../../speech'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Pager, Stat, StatusBadge, Table } from '../../components/ui'
import { daysAgo, fmtDateTime, isoDate, label } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'

interface Preset { name: string; message: string }

const TYPES: GateEventType[] = ['ALARM', 'TAMPER', 'POWER_LOSS', 'POWER_RESTORED', 'VISITOR', 'EMERGENCY_ON', 'EMERGENCY_OFF', 'BROADCAST']

export function GatePage() {
  const canEdit = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
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

  const { data: presets, setData: setPresets } = useAsync(() => api<Preset[]>('/api/gate/presets'), [])
  const [managing, setManaging] = useState(false)

  async function broadcast(text: string) {
    if (!text.trim()) return
    try {
      await api('/api/gate/broadcast', { body: { message: text } })
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
        <button className="btn" onClick={() => download(`/api/gate/export${qs({ from, to })}`, 'gate-events.csv')}><Icon name="download" />Export log</button>
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
      <div className="grid cols-2" style={{ marginBottom: 16 }}>
        <div className="card stack">
          <h2>Gate control & device health</h2>
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
                  onClick={() => confirm(`Broadcast "${p.name}" to all library displays?\n\n${p.message}`) && broadcast(p.message)}>
                  <Icon name="volume-2" />{p.name}</button>
              ))}
            </div>
          ) : <p className="muted small" style={{ margin: 0 }}>No presets yet.</p>}
        </div>
      </div>
      <VoiceCard canEdit={canEdit} />
      {managing && presets && <PresetsModal value={presets} onClose={() => setManaging(false)}
        onSaved={(p) => { setPresets(p); setManaging(false) }} />}
      <div className="card">
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
      </div>
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
          kiosk/display and read aloud. Up to 12 presets.</p>
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
          <button className="btn" onClick={() => void speak('Good day. This is a test announcement from the Z S C M S T Learning Resource Center.', v, 1)}>
            <Icon name="volume-2" />Test voice</button>
          {canEdit && <button className="btn primary" onClick={save}>Save voice</button>}
        </div>
      </>}
    </div>
  )
}

import { useState } from 'react'
import { api } from '../../api/client'
import type { GateSummary, Role, User } from '../../api/types'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Table, PasswordInput } from '../../components/ui'
import { fmtDate, fmtDateTime } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'

const STAFF_ROLES: { id: Role; text: string }[] = [
  { id: 'ADMIN', text: 'Administrator — everything incl. users & audit' },
  { id: 'LIBRARIAN', text: 'Librarian — overrides, waivers, settings' },
  { id: 'STAFF', text: 'Staff / student assistant — circulation & cataloging' },
  { id: 'CASHIER', text: 'Cashier — receives fine payments; sees only Fines & payments' },
]

const DEVICE_TYPES: { id: Role; title: string; text: string }[] = [
  { id: 'KIOSK', title: 'Kiosk', text: 'Attendance or self-checkout kiosk. Connects once with a device key.' },
  { id: 'GATE', title: 'Security gate', text: 'RFID gate or its bridge software. Connects with a device token.' },
]

const isDevice = (u: User) => u.role === 'KIOSK' || u.role === 'GATE'

const statusBadge = (u: User) => <span className="row" style={{ gap: 4 }}>
  {u.enabled ? <span className="badge ok">Enabled</span> : <span className="badge bad">Disabled</span>}
  {u.lockedUntil && <span className="badge bad" title={`Locked until ${new Date(u.lockedUntil).toLocaleTimeString()}`}>Locked</span>}
</span>

/** Random password for gate accounts, which authenticate with a device token instead. */
function randomSecret() {
  const bytes = crypto.getRandomValues(new Uint8Array(24))
  return btoa(String.fromCharCode(...bytes))
}

export function UsersPage() {
  const { data, error, reload } = useAsync(() => api<User[]>('/api/users'), [])
  const [user, setUser] = useState<Partial<User> | null>(null)
  const [device, setDevice] = useState<Partial<User> | null>(null)
  const people = data?.filter((u) => !isDevice(u))
  const devices = data?.filter(isDevice)
  const done = () => { setUser(null); setDevice(null); void reload() }

  return (
    <>
      <div className="page-head"><h1>Users & devices</h1></div>
      <ErrorAlert error={error} />

      <div className="card">
        <div className="page-head" style={{ marginBottom: 8 }}>
          <div>
            <h2 style={{ margin: 0 }}>Staff users</h2>
            <p className="muted small" style={{ margin: 0 }}>People who sign in to the staff app.</p>
          </div>
          <button className="btn primary" onClick={() => setUser({ role: 'STAFF', enabled: true })}><Icon name="plus" />Add user</button>
        </div>
        <Table rows={people} rowKey={(u) => u.id} onRowClick={setUser} empty="No staff users" columns={[
          { header: 'Username', render: (u) => <strong>{u.username}</strong> },
          { header: 'Name', render: (u) => u.fullName },
          { header: 'Role', render: (u) => <span className="badge info">{u.role}</span> },
          { header: 'Status', render: statusBadge },
          { header: 'Created', render: (u) => fmtDate(u.createdAt) },
        ]} />
      </div>

      <div className="card">
        <div className="page-head" style={{ marginBottom: 8 }}>
          <div>
            <h2 style={{ margin: 0 }}>Devices</h2>
            <p className="muted small" style={{ margin: 0 }}>Kiosks and security gates that connect to the library system.</p>
          </div>
          <button className="btn primary" onClick={() => setDevice({ role: 'KIOSK', enabled: true })}><Icon name="plus" />Add device</button>
        </div>
        <Table rows={devices} rowKey={(u) => u.id} onRowClick={setDevice} empty="No devices yet" columns={[
          { header: 'Device', render: (u) => <strong>{u.fullName}</strong> },
          { header: 'Type', render: (u) => <span className="badge info">{u.role === 'GATE' ? 'Security gate' : 'Kiosk'}</span> },
          { header: 'Username', render: (u) => <span className="mono">{u.username}</span> },
          { header: 'Status', render: statusBadge },
          { header: 'Last connected', render: (u) => (u.deviceLastSeenAt ? fmtDateTime(u.deviceLastSeenAt)
            : u.role === 'KIOSK' && !u.hasDeviceKey ? <span className="muted">No key yet</span> : '—') },
          { header: 'Added', render: (u) => fmtDate(u.createdAt) },
        ]} />
      </div>

      <SignInProtection />
      {user && <UserModal value={user} onClose={() => setUser(null)} onSaved={done} />}
      {device && <DeviceModal value={device} onClose={() => setDevice(null)} onSaved={done} />}
    </>
  )
}

function UserModal({ value, onClose, onSaved }: { value: Partial<User>; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState(value)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()

  async function save() {
    setError(null)
    try {
      if (f.id) await api(`/api/users/${f.id}`, { method: 'PUT', body: { fullName: f.fullName, role: f.role, enabled: f.enabled, password: password || null } })
      else await api('/api/users', { body: { username: f.username, fullName: f.fullName, role: f.role, password } })
      toast('User saved')
      onSaved()
    } catch (e) { setError(errorText(e)) }
  }

  return (
    <Modal title={f.id ? `Edit ${value.username}` : 'Add user'} onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" onClick={save}>Save</button></>}>
      <div className="stack">
        <ErrorAlert error={error} />
        {f.lockedUntil && (
          <div className="alert warn small row" style={{ justifyContent: 'space-between' }}>
            <span>Locked after repeated failed sign-ins (until {new Date(f.lockedUntil).toLocaleTimeString()}).</span>
            <button className="btn small" onClick={async () => {
              try { setF(await api<User>(`/api/users/${f.id}/unlock`, { method: 'POST' })); toast('Account unlocked') }
              catch (e) { setError(errorText(e)) }
            }}>Unlock now</button>
          </div>
        )}
        <div className="form-grid">
          <Field label="Username"><input value={f.username ?? ''} disabled={!!f.id} onChange={(e) => setF({ ...f, username: e.target.value })} autoComplete="off" /></Field>
          <Field label="Full name"><input value={f.fullName ?? ''} onChange={(e) => setF({ ...f, fullName: e.target.value })} /></Field>
          <Field label="Role" className="span-all">
            <select value={f.role} onChange={(e) => setF({ ...f, role: e.target.value as Role })}>
              {STAFF_ROLES.map((r) => <option key={r.id} value={r.id}>{r.text}</option>)}
            </select>
          </Field>
          <Field label={f.id ? 'Temporary password (leave empty to keep)' : 'Temporary password'} hint="At least 8 characters. It is temporary: the person must choose their own at next sign-in">
            <PasswordInput value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
          </Field>
          {f.id && <Field label="Status" as="div">
            <label className="check"><input type="checkbox" checked={!!f.enabled} onChange={(e) => setF({ ...f, enabled: e.target.checked })} /> Account enabled</label>
          </Field>}
        </div>
      </div>
    </Modal>
  )
}

function DeviceModal({ value, onClose, onSaved }: { value: Partial<User>; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState(value)
  const [token, setToken] = useState<string | null>(null)
  const [deviceKey, setDeviceKey] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  const isGate = f.role === 'GATE'

  async function issueKey(id: number, replacing: boolean) {
    if (replacing && !confirm('Replace this kiosk\'s key? The kiosk disconnects until you enter the new key or open its new setup link on it.')) return
    try {
      const r = await api<{ deviceKey: string; device: User }>(`/api/users/${id}/device-key`, { method: 'POST' })
      setF(r.device)
      setDeviceKey(r.deviceKey)
    } catch (e) { setError(errorText(e)) }
  }

  async function revokeKey(id: number) {
    if (!confirm('Revoke this kiosk\'s key? The kiosk disconnects and cannot reconnect until a new key is issued.')) return
    try {
      setF(await api<User>(`/api/users/${id}/device-key`, { method: 'DELETE' }))
      setDeviceKey(null)
      toast('Key revoked')
    } catch (e) { setError(errorText(e)) }
  }

  async function copyText(text: string, what: string) {
    try {
      await navigator.clipboard.writeText(text)
      toast(`${what} copied`)
    } catch {
      toast('Select it and copy it manually', 'warn')
    }
  }

  async function issueToken(id: number) {
    const r = await api<{ accessToken: string }>(`/api/users/${id}/device-token`, { method: 'POST' })
    setToken(r.accessToken)
  }

  async function save() {
    setError(null)
    try {
      if (f.id) {
        await api(`/api/users/${f.id}`, { method: 'PUT', body: { fullName: f.fullName, role: f.role, enabled: f.enabled, password: null } })
        toast('Device saved')
        onSaved()
        return
      }
      const created = await api<User>('/api/users', {
        // Devices connect with a token or key, never by typing a password: give them a random one.
        body: { username: f.username, fullName: f.fullName, role: f.role, password: randomSecret() },
      })
      setF(created)
      if (isGate) {
        // A gate has no screen to sign in on: hand out its token right away.
        await issueToken(created.id)
        toast('Gate added — copy its device token now')
      } else {
        await issueKey(created.id, false)
        toast('Kiosk added — set it up with its key or setup link now')
      }
    } catch (e) { setError(errorText(e)) }
  }

  async function copy() {
    if (!token) return
    try {
      await navigator.clipboard.writeText(token)
      toast('Token copied')
    } catch {
      toast('Select the token and copy it manually', 'warn')
    }
  }

  const justCreated = (token || deviceKey) && !value.id
  const closeAfterToken = justCreated || deviceKey ? onSaved : onClose

  async function revokeTokens(id: number) {
    if (!confirm('Revoke every token issued to this device? The gate stops working until you issue a new token and enter it in the gate software.')) return
    try {
      await api(`/api/users/${id}/device-token/revoke`, { method: 'POST' })
      toast('All tokens of this device were revoked')
    } catch (e) { setError(errorText(e)) }
  }

  return (
    <Modal title={f.id ? `Device: ${f.fullName}` : 'Add device'} onClose={closeAfterToken}
      footer={justCreated
        ? <button className="btn primary" onClick={onSaved}>Done</button>
        : <><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" onClick={save}>Save</button></>}>
      <div className="stack">
        <ErrorAlert error={error} />
        {!f.id && (
          <div className="grid cols-2">
            {DEVICE_TYPES.map((t) => (
              <button key={t.id} type="button" className={`card choice ${f.role === t.id ? 'selected' : ''}`}
                onClick={() => setF({ ...f, role: t.id })} aria-pressed={f.role === t.id}>
                <Icon name={t.id === 'GATE' ? 'shield' : 'monitor'} className="choice-icon" />
                <strong>{t.title}</strong>
                <span className="muted small">{t.text}</span>
              </button>
            ))}
          </div>
        )}
        <div className="form-grid">
          <Field label="Device name" hint={isGate ? 'e.g. Main entrance gate' : 'e.g. Lobby attendance kiosk'}>
            <input value={f.fullName ?? ''} onChange={(e) => setF({ ...f, fullName: e.target.value })} />
          </Field>
          <Field label="Username" hint="Used by the device to connect">
            <input className="mono" value={f.username ?? ''} disabled={!!f.id} onChange={(e) => setF({ ...f, username: e.target.value })} autoComplete="off" />
          </Field>
          {f.id && <Field label="Status" as="div">
            <label className="check"><input type="checkbox" checked={!!f.enabled} onChange={(e) => setF({ ...f, enabled: e.target.checked })} /> Device enabled</label>
          </Field>}
        </div>

        {isGate ? (
          <div className="alert small stack" style={{ gap: 8 }}>
            <span>The gate (or its bridge software) sends this token as <span className="mono">Authorization: Bearer &lt;token&gt;</span> on
              every call to <span className="mono">/api/gate/device/*</span>. Its lifetime is set in System settings
              (default one year). Disabling the device stops its tokens at once.</span>
            {f.id && !token && <div className="row">
              <button className="btn small" onClick={() => issueToken(f.id!).catch((e) => setError(errorText(e)))}>
                <Icon name="key" />Issue new device token</button>
              <button className="btn small danger" onClick={() => revokeTokens(f.id!)}>Revoke all tokens</button>
            </div>}
            {token && <>
              <textarea readOnly className="mono small" value={token} onFocus={(e) => e.target.select()} />
              <div className="row"><button className="btn small primary" onClick={copy}>Copy token</button>
                <span className="muted">Keep it secret, like a password. It is not shown again.</span></div>
            </>}
            <GateConnectSteps token={token} gateId={(f.username ?? '').trim().toUpperCase() || 'GATE-1'} />
          </div>
        ) : f.id ? (
          <div className="alert small stack" style={{ gap: 8 }}>
            <span><strong>Device key.</strong> The kiosk connects with this key instead of a password and stays connected until the
              key is replaced or revoked, or the device is disabled. {f.hasDeviceKey
                ? <>Issued {fmtDateTime(f.deviceKeyIssuedAt)}{f.deviceLastSeenAt ? <> · last connected {fmtDateTime(f.deviceLastSeenAt)}</> : ' · not connected yet'}.</>
                : 'No key issued yet.'}</span>
            {deviceKey ? (
              <>
                <span>On the kiosk PC, open one of these setup links (it saves the key and starts the kiosk), or open the kiosk page and paste the key:</span>
                {[['Attendance kiosk', '/kiosk/attendance'], ['Self-checkout kiosk', '/kiosk/selfcheck']].map(([label, path]) => {
                  // Key in the fragment: browsers never send it, so it stays out of server access logs.
                  const link = `${window.location.origin}${path}#key=${encodeURIComponent(deviceKey)}`
                  return (
                    <div key={path} className="row" style={{ flexWrap: 'nowrap' }}>
                      <input readOnly className="mono small" value={link} onFocus={(e) => e.target.select()} aria-label={`${label} setup link`} />
                      <button className="btn small" onClick={() => copyText(link, `${label} link`)}>Copy {label.toLowerCase()} link</button>
                    </div>
                  )
                })}
                <div className="row" style={{ flexWrap: 'nowrap' }}>
                  <input readOnly className="mono small" value={deviceKey} onFocus={(e) => e.target.select()} aria-label="Device key" />
                  <button className="btn small primary" onClick={() => copyText(deviceKey, 'Key')}>Copy key</button>
                </div>
                <span className="muted">Treat it like a password. It is shown only now; if it is lost, replace it.</span>
              </>
            ) : (
              <div className="row">
                <button className="btn small" onClick={() => issueKey(f.id!, !!f.hasDeviceKey)}>
                  <Icon name="key" />{f.hasDeviceKey ? 'Replace key' : 'Issue key'}</button>
                {f.hasDeviceKey && <button className="btn small danger" onClick={() => revokeKey(f.id!)}>Revoke key</button>}
              </div>
            )}
            <span className="muted">The public search kiosk (<span className="mono">/opac</span>) needs no key.</span>
          </div>
        ) : (
          <div className="alert small">After saving, you get this kiosk's device key and setup links to open on the kiosk PC.</div>
        )}
      </div>
    </Modal>
  )
}

/** Lock accounts after repeated failed sign-ins (staff passwords and patron PINs). */
function SignInProtection() {
  const { data, setData } = useAsync(() => api<{ maxAttempts: number; lockMinutes: number }>('/api/users/security-settings'), [])
  const toast = useToast()
  if (!data) return null
  async function save() {
    try {
      setData(await api<{ maxAttempts: number; lockMinutes: number }>('/api/users/security-settings', { method: 'PUT', body: data }))
      toast('Sign-in protection saved')
    } catch (e) { toast(errorText(e), 'error') }
  }
  return (
    <div className="card stack">
      <h2 style={{ margin: 0 }}>Sign-in protection</h2>
      <p className="muted small" style={{ margin: 0 }}>After this many wrong passwords or PINs in a row, the account is locked for a while.
        Unlock staff here (open the user) and patrons by setting a new PIN.</p>
      <div className="form-grid">
        <Field label="Failed attempts before locking"><input type="number" min={3} max={20} value={data.maxAttempts}
          onChange={(e) => setData({ ...data, maxAttempts: Number(e.target.value) })} /></Field>
        <Field label="Lock duration (minutes)"><input type="number" min={1} max={1440} value={data.lockMinutes}
          onChange={(e) => setData({ ...data, lockMinutes: Number(e.target.value) })} /></Field>
      </div>
      <div className="row end"><button className="btn primary" onClick={save}>Save</button></div>
    </div>
  )
}

/**
 * How to connect a security gate of any brand: the RFID bridge runs on the PC wired to the gate and calls the
 * library with this device's token. Also lists the gates reporting to the library, so staff can see it worked.
 */
function GateConnectSteps({ token, gateId }: { token: string | null; gateId: string }) {
  const { data } = useAsync(() => api<GateSummary>('/api/gate/summary'), [])
  const settings = [
    'mode=gate',
    `gate.id=${gateId}`,
    `lms.url=${window.location.origin}`,
    `lms.deviceKey=${token ?? '<paste the device token here>'}`,
    '# Tries every reader type at once and uses whichever sends tags.',
    '# Fill in only what applies to your gate (step 6).',
    'reader=auto',
    '#serial.device=COM4',
    '#tcp.host=192.168.1.60',
    '#tcp.port=6000',
    '#exec.inventory="C:/Program Files/Vendor/gate-cli.exe" stream',
    '#gate.alarm="C:/Program Files/Vendor/gate-cli.exe" alarm --seconds 5',
  ].join('\n')
  return (
    <details>
      <summary style={{ cursor: 'pointer' }}><strong>How to connect a gate (any brand)</strong></summary>
      <div className="stack" style={{ gap: 6, marginTop: 8 }}>
        <span>The gate connects to the library, not the other way round: the <strong>RFID bridge</strong> program runs on the
          PC wired to the gate, reads its tags, asks the library if each item is borrowed, and sounds the alarm if not.</span>

        <strong>On the library system (once per gate)</strong>
        <ol style={{ margin: 0, paddingLeft: 20 }}>
          <li>Click <em>Issue new device token</em> above, then <em>Copy token</em>. One device per gate: for another gate, use
            <em> Add device → Security gate</em>.</li>
          <li>Copy the whole settings box below. It has this library's address, and the token once you have issued it.</li>
        </ol>
        <textarea readOnly className="mono small" rows={8} value={settings} onFocus={(e) => e.target.select()} />

        <strong>On the PC wired to the gate</strong>
        <ol start={3} style={{ margin: 0, paddingLeft: 20 }}>
          <li><strong>Install Java 21 or newer</strong>: Eclipse Temurin, free at{' '}
            <a href="https://adoptium.net" target="_blank" rel="noreferrer">adoptium.net</a> (Windows installer, Next through it).</li>
          <li><strong>Get the bridge</strong>: from the{' '}
            <a href="https://github.com/Glenn74813/library" target="_blank" rel="noreferrer">system's GitHub repository</a>,
            {' '}<em>Code → Download ZIP</em>, unzip it, and copy just the <span className="mono">rfid-bridge</span> folder to the PC,
            e.g. <span className="mono">C:\rfid-bridge</span>.</li>
          <li>In that folder, create <span className="mono">rfid-bridge.properties</span> (make sure Windows doesn't add
            <span className="mono"> .txt</span>) and paste the settings into it.</li>
          <li><strong>Fill in only what applies</strong> (remove the <span className="mono">#</span> in front of the line):
            <ul style={{ margin: '2px 0', paddingLeft: 18 }}>
              <li>Gate plugged in by USB: nothing. Auto finds the port.</li>
              <li>Network gate: <span className="mono">tcp.host</span> and <span className="mono">tcp.port</span>, the gate's IP
                and port (from the vendor or the gate's manual).</li>
              <li>The vendor gave you a program: <span className="mono">exec.inventory</span>, pointing to it.</li>
              <li>The bridge should sound the alarm: <span className="mono">gate.alarm</span>, the vendor's alarm command. Skip it
                if the gate beeps on its own.</li>
            </ul>
            Write paths with forward slashes (<span className="mono">C:/Program Files/...</span>).</li>
          <li><strong>Start it</strong>: open Command Prompt in that folder and run <span className="mono">java RfidBridge.java</span>.
            It shows <span className="mono">Auto reader: watching ...</span>, then <span className="mono">Connected to ...</span> once
            the gate sends data. Leave the window open.</li>
          <li><strong>Start it with Windows</strong>: in Task Scheduler, add a task <em>At log on</em> that runs program
            {' '}<span className="mono">java</span>, argument <span className="mono">RfidBridge.java</span>, start in
            {' '}<span className="mono">C:\rfid-bridge</span>.</li>
        </ol>

        <strong>Check it works</strong>
        <ol start={9} style={{ margin: 0, paddingLeft: 20 }}>
          <li>Within a minute, <em>Gates reporting now</em> below shows <span className="mono">{gateId} · online</span>.</li>
          <li>Walk through the gate with a copy that is <strong>not</strong> checked out: an <strong>ALARM</strong> with its title
            appears on the Security gate page. A borrowed copy passes quietly.</li>
        </ol>
        <span className="muted"><strong>No gate yet?</strong> Change <span className="mono">reader=auto</span> to
          {' '}<span className="mono">reader=simulator</span>, run the bridge, and type <span className="mono">place</span> and a tag
          number in its window: it acts like a tag passing the gate. <strong>Online but no tags?</strong> The gate isn't sending tag
          numbers in a form the bridge can read: ask the vendor how it outputs reads, or for a command-line tool that prints them.</span>
        <span><strong>Gates reporting now:</strong>{' '}
          {data?.gates.length
            ? data.gates.map((g) => <span key={g.gateId} className={`badge ${g.online ? 'ok' : ''}`} style={{ marginRight: 6 }}>
                {g.gateId} · {g.online ? 'online' : `last seen ${fmtDateTime(g.lastSeen)}`}</span>)
            : <span className="muted">none yet</span>}</span>
      </div>
    </details>
  )
}

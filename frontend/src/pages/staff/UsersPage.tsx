import { useState } from 'react'
import { api } from '../../api/client'
import type { Role, User } from '../../api/types'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Table } from '../../components/ui'
import { fmtDate } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'

const STAFF_ROLES: { id: Role; text: string }[] = [
  { id: 'ADMIN', text: 'Administrator — everything incl. users & audit' },
  { id: 'LIBRARIAN', text: 'Librarian — overrides, waivers, settings' },
  { id: 'STAFF', text: 'Staff / student assistant — circulation & cataloging' },
]

const DEVICE_TYPES: { id: Role; title: string; text: string }[] = [
  { id: 'KIOSK', title: 'Attendance kiosk', text: 'Time-in / time-out kiosk. Signs in once on the kiosk screen.' },
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
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
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
  const [password, setPassword] = useState('')
  const [token, setToken] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  const isGate = f.role === 'GATE'

  async function issueToken(id: number) {
    const r = await api<{ accessToken: string }>(`/api/users/${id}/device-token`, { method: 'POST' })
    setToken(r.accessToken)
  }

  async function save() {
    setError(null)
    try {
      if (f.id) {
        await api(`/api/users/${f.id}`, { method: 'PUT', body: { fullName: f.fullName, role: f.role, enabled: f.enabled, password: password || null } })
        toast('Device saved')
        onSaved()
        return
      }
      const created = await api<User>('/api/users', {
        body: { username: f.username, fullName: f.fullName, role: f.role, password: isGate ? randomSecret() : password },
      })
      if (isGate) {
        // A gate has no screen to sign in on: hand out its token right away.
        setF(created)
        await issueToken(created.id)
        toast('Gate added — copy its device token now')
      } else {
        toast('Kiosk added')
        onSaved()
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

  const closeAfterToken = token && !value.id ? onSaved : onClose

  async function revokeTokens(id: number) {
    if (!confirm('Revoke every token issued to this device? The gate stops working until you issue a new token and enter it in the gate software.')) return
    try {
      await api(`/api/users/${id}/device-token/revoke`, { method: 'POST' })
      toast('All tokens of this device were revoked')
    } catch (e) { setError(errorText(e)) }
  }

  return (
    <Modal title={f.id ? `Device: ${f.fullName}` : 'Add device'} onClose={closeAfterToken}
      footer={token && !value.id
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
          {!isGate && (
            <Field label={f.id ? 'New password (leave empty to keep)' : 'Password'} hint="Entered once on the kiosk's setup screen">
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
            </Field>
          )}
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
          </div>
        ) : (
          <div className="alert small">
            Attendance kiosk: open <span className="mono">/kiosk/attendance</span> on the kiosk PC and sign in once with this username and
            password; it then stays signed in. The OPAC search kiosk (<span className="mono">/opac?kiosk=1</span>) is public and needs no sign-in.
          </div>
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

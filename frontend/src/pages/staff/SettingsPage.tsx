import { useState } from 'react'
import { api } from '../../api/client'
import { MATERIAL_TYPES, type Category, type Course, type MaterialType } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Table } from '../../components/ui'
import { fmtMoney, label } from '../../format'
import { useAsync } from '../../hooks/useAsync'
import { Icon } from '../../components/Icon'

export function SettingsPage() {
  const canEdit = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  const { data: categories, reload: reloadCats } = useAsync(() => api<Category[]>('/api/patron-categories'), [])
  const { data: courses, reload: reloadCourses } = useAsync(() => api<Course[]>('/api/courses'), [])
  const [cat, setCat] = useState<Partial<Category> | null>(null)
  const [course, setCourse] = useState<Partial<Course> | null>(null)

  return (
    <>
      <div className="page-head"><h1>Loan rules & courses</h1></div>
      <LibraryRulesCard canEdit={canEdit} />
      <div className="card">
        <div className="page-head" style={{ marginBottom: 8 }}>
          <h2 style={{ margin: 0 }}>Patron categories (loan rules)</h2>
          {canEdit && <button className="btn primary" onClick={() => setCat({ loanDays: 7, maxLoans: 3, maxRenewals: 1, finePerDay: 5, maxUnpaidFines: 100, holdPickupDays: 3, maxHolds: 0, fineGraceDays: 0, maxFinePerItem: 0 })}><Icon name="plus" />Add</button>}
        </div>
        <Table rows={categories} rowKey={(c) => c.id} onRowClick={canEdit ? setCat : undefined} columns={[
          { header: 'Category', render: (c) => <strong>{c.name}</strong> },
          { header: 'Loan period', render: (c) => `${c.loanDays} days` },
          { header: 'Max items', render: (c) => c.maxLoans },
          { header: 'Renewals', render: (c) => c.maxRenewals },
          { header: 'Reservations', render: (c) => (c.maxHolds ? c.maxHolds : 'No limit') },
          { header: 'Pickup window', render: (c) => `${c.holdPickupDays} day(s)` },
          { header: 'Fine / day', render: (c) => fmtMoney(c.finePerDay) },
          { header: 'Grace', render: (c) => (c.fineGraceDays ? `${c.fineGraceDays} day(s)` : '—') },
          { header: 'Max fine / item', render: (c) => (c.maxFinePerItem ? fmtMoney(c.maxFinePerItem) : 'No cap') },
          { header: 'Unpaid fines limit', render: (c) => fmtMoney(c.maxUnpaidFines) },
        ]} />
      </div>
      <div className="card">
        <div className="page-head" style={{ marginBottom: 8 }}>
          <h2 style={{ margin: 0 }}>Programs / courses</h2>
          {canEdit && <button className="btn primary" onClick={() => setCourse({})}><Icon name="plus" />Add</button>}
        </div>
        <Table rows={courses} rowKey={(c) => c.id} onRowClick={canEdit ? setCourse : undefined} empty="No courses yet" columns={[
          { header: 'Code', render: (c) => <strong>{c.code}</strong> },
          { header: 'Name', render: (c) => c.name },
        ]} />
      </div>
      {cat && <CategoryModal value={cat} onClose={() => setCat(null)} onSaved={() => { setCat(null); void reloadCats() }} />}
      {course && <CourseModal value={course} onClose={() => setCourse(null)} onSaved={() => { setCourse(null); void reloadCourses() }} />}
    </>
  )
}

function CategoryModal({ value, onClose, onSaved }: { value: Partial<Category>; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState(value)
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  const num = (k: keyof Category) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: Number(e.target.value) })
  async function save() {
    try {
      if (f.id) await api(`/api/patron-categories/${f.id}`, { method: 'PUT', body: f })
      else await api('/api/patron-categories', { body: f })
      toast('Saved')
      onSaved()
    } catch (e) { setError(errorText(e)) }
  }
  async function remove() {
    if (!confirm('Delete this category?')) return
    try { await api(`/api/patron-categories/${f.id}`, { method: 'DELETE' }); onSaved() } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={f.id ? `Edit ${value.name}` : 'New category'} onClose={onClose} footer={<>
      {f.id && <button className="btn danger" onClick={remove}>Delete</button>}<span className="spacer" />
      <button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" onClick={save}>Save</button></>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <div className="form-grid">
          <Field label="Name" className="span-all"><input value={f.name ?? ''} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
          <Field label="Loan period (days)"><input type="number" min={1} value={f.loanDays ?? ''} onChange={num('loanDays')} /></Field>
          <Field label="Max items on loan"><input type="number" min={0} value={f.maxLoans ?? ''} onChange={num('maxLoans')} /></Field>
          <Field label="Max renewals"><input type="number" min={0} value={f.maxRenewals ?? ''} onChange={num('maxRenewals')} /></Field>
          <Field label="Pickup window (days)" hint="How long a reserved or booked copy waits on the hold shelf before it is released">
            <input type="number" min={1} max={30} value={f.holdPickupDays ?? ''} onChange={num('holdPickupDays')} /></Field>
          <Field label="Max reservations and bookings" hint="Open reservations a patron may have at once; 0 = no limit">
            <input type="number" min={0} max={100} value={f.maxHolds ?? 0} onChange={num('maxHolds')} /></Field>
          <Field label="Overdue fine per day (PHP)" hint="Charged per late open day when the item is returned; 0 = no fines"><input type="number" min={0} step="0.01" value={f.finePerDay ?? ''} onChange={num('finePerDay')} /></Field>
          <Field label="Grace period (days)" hint="This many late open days are not charged (e.g. 1 = one free late day)">
            <input type="number" min={0} max={60} value={f.fineGraceDays ?? 0} onChange={num('fineGraceDays')} /></Field>
          <Field label="Max overdue fine per item (PHP)" hint="The overdue fine for one item never exceeds this; 0 = no cap">
            <input type="number" min={0} step="0.01" value={f.maxFinePerItem ?? 0} onChange={num('maxFinePerItem')} /></Field>
          <Field label="Unpaid fines limit (PHP)" hint="New check-outs are refused while unpaid fines are above this amount"><input type="number" min={0} step="0.01" value={f.maxUnpaidFines ?? ''} onChange={num('maxUnpaidFines')} /></Field>
        </div>
      </div>
    </Modal>
  )
}

function CourseModal({ value, onClose, onSaved }: { value: Partial<Course>; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState(value)
  const [error, setError] = useState<string | null>(null)
  async function save() {
    try {
      if (f.id) await api(`/api/courses/${f.id}`, { method: 'PUT', body: f })
      else await api('/api/courses', { body: f })
      onSaved()
    } catch (e) { setError(errorText(e)) }
  }
  async function remove() {
    if (!confirm('Delete this course?')) return
    try { await api(`/api/courses/${f.id}`, { method: 'DELETE' }); onSaved() } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={f.id ? `Edit ${value.code}` : 'New course'} onClose={onClose} footer={<>
      {f.id && <button className="btn danger" onClick={remove}>Delete</button>}<span className="spacer" />
      <button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" onClick={save}>Save</button></>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <Field label="Code (e.g. BSMT)"><input value={f.code ?? ''} onChange={(e) => setF({ ...f, code: e.target.value })} /></Field>
        <Field label="Name"><input value={f.name ?? ''} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
      </div>
    </Modal>
  )
}

interface CirculationSettings {
  roomUseTypes: MaterialType[]
  typeLoanDays: Partial<Record<MaterialType, number>>
  bookingHorizonDays: number
  atRiskDays: number
  defaultLostFee: number
}

/** Library-wide rules: room-use-only and loan periods per material type, bookings, lost items. */
function LibraryRulesCard({ canEdit }: { canEdit: boolean }) {
  const { data, setData } = useAsync(() => api<CirculationSettings>('/api/circulation/settings'), [])
  const toast = useToast()
  if (!data) return null
  const set = (patch: Partial<CirculationSettings>) => setData({ ...data, ...patch })
  const toggle = (t: MaterialType) => set({ roomUseTypes: data.roomUseTypes.includes(t)
    ? data.roomUseTypes.filter((x) => x !== t) : [...data.roomUseTypes, t] })
  const setDays = (t: MaterialType, v: string) => {
    const next = { ...data.typeLoanDays }
    if (Number(v) > 0) next[t] = Number(v)
    else delete next[t]
    set({ typeLoanDays: next })
  }
  async function save() {
    try {
      setData(await api<CirculationSettings>('/api/circulation/settings', { method: 'PUT', body: data }))
      toast('Library-wide rules saved')
    } catch (e) { toast(errorText(e), 'error') }
  }
  return (
    <div className="card stack">
      <h2 style={{ margin: 0 }}>Library-wide rules</h2>
      <fieldset disabled={!canEdit} className="stack" style={{ border: 0, padding: 0, margin: 0 }}>
        <div>
          <h3 style={{ margin: '0 0 4px' }}>By material type</h3>
          <p className="muted small" style={{ margin: '0 0 8px' }}><strong>Room use only</strong> types can be read in the library but not
            borrowed or reserved (a single copy can override this). A <strong>loan period</strong> here replaces the patron
            category's for that type, e.g. audiovisual 3 days; leave it empty to use the category's.</p>
          <Table rows={MATERIAL_TYPES.map((t) => ({ t }))} rowKey={(r) => r.t} columns={[
            { header: 'Material type', render: (r) => label(r.t) },
            { header: 'Room use only', render: (r) => <label className="check"><input type="checkbox" aria-label={`${label(r.t)} room use only`}
              checked={data.roomUseTypes.includes(r.t)} onChange={() => toggle(r.t)} /> {data.roomUseTypes.includes(r.t) ? 'Yes' : 'No'}</label> },
            { header: 'Loan period (days)', render: (r) => data.roomUseTypes.includes(r.t) ? <span className="muted">—</span>
              : <input type="number" min={0} max={365} style={{ maxWidth: 120 }} placeholder="Category" aria-label={`${label(r.t)} loan period`}
                value={data.typeLoanDays[r.t] ?? ''} onChange={(e) => setDays(r.t, e.target.value)} /> },
          ]} />
        </div>
        <div className="form-grid">
          <Field label="Bookings: how far ahead (days)" hint="Patrons can book a pickup date up to this many days ahead">
            <input type="number" min={1} max={365} value={data.bookingHorizonDays} onChange={(e) => set({ bookingHorizonDays: Number(e.target.value) })} /></Field>
          <Field label="At-risk warning (days before pickup)" hint="Warn staff this many days ahead when a booked copy may not be back in time">
            <input type="number" min={0} max={30} value={data.atRiskDays} onChange={(e) => set({ atRiskDays: Number(e.target.value) })} /></Field>
          <Field label="Default lost-item charge (PHP)" hint="Suggested when marking a loan lost; staff can change it each time">
            <input type="number" min={0} step="0.01" value={data.defaultLostFee} onChange={(e) => set({ defaultLostFee: Number(e.target.value) })} /></Field>
        </div>
      </fieldset>
      {canEdit && <div className="row end"><button className="btn primary" onClick={save}>Save</button></div>}
    </div>
  )
}

import { useState } from 'react'
import { api } from '../../api/client'
import type { Category, Course } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Table } from '../../components/ui'
import { fmtMoney } from '../../format'
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
      <div className="card">
        <div className="page-head" style={{ marginBottom: 8 }}>
          <h2 style={{ margin: 0 }}>Patron categories (loan rules)</h2>
          {canEdit && <button className="btn primary" onClick={() => setCat({ loanDays: 7, maxLoans: 3, maxRenewals: 1, finePerDay: 5, maxUnpaidFines: 100 })}><Icon name="plus" />Add</button>}
        </div>
        <Table rows={categories} rowKey={(c) => c.id} onRowClick={canEdit ? setCat : undefined} columns={[
          { header: 'Category', render: (c) => <strong>{c.name}</strong> },
          { header: 'Loan period', render: (c) => `${c.loanDays} days` },
          { header: 'Max items', render: (c) => c.maxLoans },
          { header: 'Renewals', render: (c) => c.maxRenewals },
          { header: 'Fine / day', render: (c) => fmtMoney(c.finePerDay) },
          { header: 'Block at unpaid', render: (c) => fmtMoney(c.maxUnpaidFines) },
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
          <Field label="Overdue fine per day (PHP)"><input type="number" min={0} step="0.01" value={f.finePerDay ?? ''} onChange={num('finePerDay')} /></Field>
          <Field label="Block borrowing above unpaid (PHP)"><input type="number" min={0} step="0.01" value={f.maxUnpaidFines ?? ''} onChange={num('maxUnpaidFines')} /></Field>
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

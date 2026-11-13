import { useState } from 'react'
import { api } from '../api/client'
import type { Category, Course, Patron } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { errorText, useToast } from './Toast'
import { ErrorAlert, Field, Modal } from './ui'

type Form = {
  cardNumber: string; rfidUid: string; firstName: string; lastName: string; email: string; phone: string
  categoryId: number | ''; courseId: number | ''; photoUrl: string; expiryDate: string; blocked: boolean; blockReason: string
}

export function PatronFormModal({ patron, onClose, onSaved }: {
  patron: Patron | null; onClose: () => void; onSaved: (p: Patron) => void
}) {
  const { data: categories } = useAsync(() => api<Category[]>('/api/patron-categories'), [])
  const { data: courses } = useAsync(() => api<Course[]>('/api/courses'), [])
  const [f, setF] = useState<Form>({
    cardNumber: patron?.cardNumber ?? '', rfidUid: patron?.rfidUid ?? '', firstName: patron?.firstName ?? '',
    lastName: patron?.lastName ?? '', email: patron?.email ?? '', phone: patron?.phone ?? '',
    categoryId: patron?.categoryId ?? '', courseId: patron?.courseId ?? '', photoUrl: patron?.photoUrl ?? '',
    expiryDate: patron?.expiryDate ?? '', blocked: patron?.blocked ?? false, blockReason: patron?.blockReason ?? '',
  })
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  const set = <K extends keyof Form>(k: K, v: Form[K]) => setF((x) => ({ ...x, [k]: v }))

  async function save() {
    setError(null)
    const body = { ...f, categoryId: f.categoryId || null, courseId: f.courseId || null, expiryDate: f.expiryDate || null }
    try {
      const saved = patron
        ? await api<Patron>(`/api/patrons/${patron.id}`, { method: 'PUT', body })
        : await api<Patron>('/api/patrons', { body })
      toast(!patron && !f.cardNumber.trim() ? `Patron registered with library ID number ${saved.cardNumber}` : 'Patron saved')
      onSaved(saved)
    } catch (e) {
      setError(errorText(e))
    }
  }

  return (
    <Modal title={patron ? `Edit ${patron.fullName}` : 'Register patron'} onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" onClick={save}>Save</button></>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <div className="form-grid">
          <Field label={patron ? 'Library ID number *' : 'Library ID number'} hint={patron ? undefined : 'Leave empty to assign the next number'}>
            <input className="mono" value={f.cardNumber} onChange={(e) => set('cardNumber', e.target.value)} autoFocus
              placeholder={patron ? undefined : 'Assigned when saved'} /></Field>
          <Field label="RFID tag UID" hint="Tap the library ID on the reader"><input className="mono" value={f.rfidUid} onChange={(e) => set('rfidUid', e.target.value)} /></Field>
          <Field label="First name *"><input value={f.firstName} onChange={(e) => set('firstName', e.target.value)} /></Field>
          <Field label="Last name *"><input value={f.lastName} onChange={(e) => set('lastName', e.target.value)} /></Field>
          <Field label="Category *">
            <select value={f.categoryId} onChange={(e) => set('categoryId', e.target.value ? Number(e.target.value) : '')}>
              <option value="">Select…</option>
              {categories?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </Field>
          <Field label="Program / course">
            <select value={f.courseId} onChange={(e) => set('courseId', e.target.value ? Number(e.target.value) : '')}>
              <option value="">None</option>
              {courses?.map((c) => <option key={c.id} value={c.id}>{c.code} — {c.name}</option>)}
            </select>
          </Field>
          <Field label="Email"><input type="email" value={f.email} onChange={(e) => set('email', e.target.value)} /></Field>
          <Field label="Mobile number"><input type="tel" value={f.phone} onChange={(e) => set('phone', e.target.value)} /></Field>
          <Field label="Library ID expiry date"><input type="date" value={f.expiryDate} onChange={(e) => set('expiryDate', e.target.value)} /></Field>

          <label className="check span-all"><input type="checkbox" checked={f.blocked} onChange={(e) => set('blocked', e.target.checked)} /> Block borrowing</label>
          {f.blocked && <Field label="Block reason" className="span-all"><input value={f.blockReason} onChange={(e) => set('blockReason', e.target.value)} /></Field>}
        </div>
      </div>
    </Modal>
  )
}

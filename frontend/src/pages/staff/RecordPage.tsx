import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../../api/client'
import { ITEM_STATUSES, MATERIAL_TYPES, type BibRecord, type IsbnResult, type Item, type RecordDetail } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, StatusBadge, Table } from '../../components/ui'
import { fmtDate, label } from '../../format'
import { DigitalPanel } from '../../components/DigitalPanel'
import { Icon } from '../../components/Icon'

type RecordForm = Omit<BibRecord, 'id' | 'totalCopies' | 'availableCopies' | 'createdAt' | 'updatedAt' | 'ebook'>

const EMPTY: RecordForm = {
  title: '', subtitle: '', authors: '', isbn: '', publisher: '', publicationYear: null, edition: '', subjects: '',
  callNumber: '', materialType: 'BOOK', language: 'English', summary: '',
}

export function RecordPage() {
  const { id } = useParams()
  const isNew = !id
  const navigate = useNavigate()
  const toast = useToast()
  const canDelete = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  const [form, setForm] = useState<RecordForm>(EMPTY)
  const [items, setItems] = useState<Item[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [editingItem, setEditingItem] = useState<Partial<Item> | null>(null)

  async function load() {
    if (isNew) return
    try {
      const d = await api<RecordDetail>(`/api/catalog/records/${id}`)
      setForm(Object.fromEntries(Object.keys(EMPTY).map((k) => [k, d.record[k as keyof RecordForm]])) as RecordForm)
      setItems(d.items)
    } catch (e) {
      setError(errorText(e))
    }
  }
  useEffect(() => { void load() }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  const set = <K extends keyof RecordForm>(k: K, v: RecordForm[K]) => setForm((f) => ({ ...f, [k]: v }))

  async function lookupIsbn() {
    if (!form.isbn) return toast('Enter an ISBN first', 'warn')
    try {
      const r = await api<IsbnResult>(`/api/catalog/isbn/${encodeURIComponent(form.isbn)}`)
      setForm((f) => ({
        ...f,
        title: r.title ?? f.title, subtitle: r.subtitle ?? f.subtitle, authors: r.authors ?? f.authors,
        publisher: r.publisher ?? f.publisher, publicationYear: r.publicationYear ?? f.publicationYear,
        subjects: r.subjects ?? f.subjects, isbn: r.isbn,
      }))
      toast('Bibliographic data filled from Open Library — please review')
    } catch (e) {
      toast(errorText(e) === 'ISBN not found' ? 'No record found for this ISBN' : errorText(e), 'warn')
    }
  }

  async function save(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (isNew) {
        const r = await api<BibRecord>('/api/catalog/records', { body: form })
        toast('Record created — now add copies')
        navigate(`/staff/catalog/${r.id}`, { replace: true })
      } else {
        await api(`/api/catalog/records/${id}`, { method: 'PUT', body: form })
        toast('Record saved')
      }
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    if (!confirm('Delete this record and all of its copies? This cannot be undone.')) return
    try {
      await api(`/api/catalog/records/${id}`, { method: 'DELETE' })
      toast('Record deleted')
      navigate('/staff/catalog')
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  return (
    <>
      <div className="page-head">
        <h1>{isNew ? 'New catalog record' : form.title || 'Record'}</h1>
        <div className="row">
          <button className="btn" onClick={() => navigate('/staff/catalog')}><Icon name="chevron-left" />Back</button>
          {!isNew && canDelete && <button className="btn danger" onClick={remove}>Delete</button>}
        </div>
      </div>
      <form className="card stack" onSubmit={save}>
        <ErrorAlert error={error} />
        <div className="form-grid">
          <Field label="ISBN" hint="Scan or type, then use lookup to copy-catalog">
            <div className="input-group">
              <input value={form.isbn ?? ''} onChange={(e) => set('isbn', e.target.value)} />
              <button type="button" className="btn" onClick={lookupIsbn}>Lookup</button>
            </div>
          </Field>
          <Field label="Material type">
            <select value={form.materialType} onChange={(e) => set('materialType', e.target.value as RecordForm['materialType'])}>
              {MATERIAL_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}
            </select>
          </Field>
          <Field label="Title *" className="span-all"><input value={form.title} onChange={(e) => set('title', e.target.value)} required /></Field>
          <Field label="Subtitle" className="span-all"><input value={form.subtitle ?? ''} onChange={(e) => set('subtitle', e.target.value)} /></Field>
          <Field label="Authors" hint="Separate with ;" className="span-all"><input value={form.authors ?? ''} onChange={(e) => set('authors', e.target.value)} /></Field>
          <Field label="Publisher"><input value={form.publisher ?? ''} onChange={(e) => set('publisher', e.target.value)} /></Field>
          <Field label="Year"><input type="number" inputMode="numeric" value={form.publicationYear ?? ''}
            onChange={(e) => set('publicationYear', e.target.value ? Number(e.target.value) : null)} /></Field>
          <Field label="Edition"><input value={form.edition ?? ''} onChange={(e) => set('edition', e.target.value)} /></Field>
          <Field label="Call number"><input value={form.callNumber ?? ''} onChange={(e) => set('callNumber', e.target.value)} /></Field>
          <Field label="Language"><input value={form.language ?? ''} onChange={(e) => set('language', e.target.value)} /></Field>
          <Field label="Subjects" hint="Separate with ;" className="span-all"><input value={form.subjects ?? ''} onChange={(e) => set('subjects', e.target.value)} /></Field>
          <Field label="Summary / abstract" className="span-all"><textarea value={form.summary ?? ''} onChange={(e) => set('summary', e.target.value)} /></Field>
        </div>
        <div className="row end actions-stretch"><button className="btn primary" disabled={busy}>{isNew ? 'Create record' : 'Save changes'}</button></div>
      </form>

      {!isNew && <DigitalPanel recordId={Number(id)} />}

      {!isNew && (
        <div className="card">
          <div className="page-head" style={{ marginBottom: 8 }}>
            <h2 style={{ margin: 0 }}>Copies ({items.length})</h2>
            <button className="btn primary" onClick={() => setEditingItem({ status: 'AVAILABLE', location: 'General Circulation' })}><Icon name="plus" />Add copy</button>
          </div>
          <Table rows={items} rowKey={(i) => i.id} onRowClick={setEditingItem} empty="No copies yet" columns={[
            { header: 'Barcode', render: (i) => <span className="mono">{i.barcode}</span> },
            { header: 'RFID EPC', render: (i) => <span className="mono small">{i.rfidEpc ?? '—'}</span> },
            { header: 'Location', render: (i) => i.location ?? '—' },
            { header: 'Status', render: (i) => <><StatusBadge status={i.status} />{i.dueAt && <span className="small muted"> due {fmtDate(i.dueAt)}</span>}</> },
          ]} />
        </div>
      )}
      {editingItem && <ItemModal recordId={Number(id)} item={editingItem} canDelete={canDelete}
        onClose={() => setEditingItem(null)} onSaved={() => { setEditingItem(null); void load() }} />}
    </>
  )
}

function ItemModal({ recordId, item, canDelete, onClose, onSaved }: {
  recordId: number; item: Partial<Item>; canDelete: boolean; onClose: () => void; onSaved: () => void
}) {
  const [f, setF] = useState<Partial<Item>>(item)
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  const set = <K extends keyof Item>(k: K, v: Item[K] | null) => setF((x) => ({ ...x, [k]: v }))
  const circulating = f.status === 'ON_LOAN' || f.status === 'ON_HOLD'

  async function save() {
    setError(null)
    const body = { barcode: f.barcode, rfidEpc: f.rfidEpc, location: f.location, status: f.status,
      acquiredDate: f.acquiredDate || null, price: f.price ?? null, notes: f.notes }
    try {
      if (item.id) await api(`/api/catalog/items/${item.id}`, { method: 'PUT', body })
      else await api(`/api/catalog/records/${recordId}/items`, { body })
      toast('Copy saved')
      onSaved()
    } catch (e) {
      setError(errorText(e))
    }
  }

  async function remove() {
    if (!confirm('Delete this copy?')) return
    try {
      await api(`/api/catalog/items/${item.id}`, { method: 'DELETE' })
      onSaved()
    } catch (e) {
      setError(errorText(e))
    }
  }

  return (
    <Modal title={item.id ? `Copy ${item.barcode}` : 'Add copy'} onClose={onClose} footer={<>
      {item.id && canDelete && <button className="btn danger" onClick={remove}>Delete</button>}
      <span className="spacer" />
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn primary" onClick={save}>Save</button>
    </>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <div className="form-grid">
          <Field label="Barcode *"><input className="mono" value={f.barcode ?? ''} onChange={(e) => set('barcode', e.target.value)} autoFocus /></Field>
          <Field label="RFID tag EPC" hint="Read with the tagging device"><input className="mono" value={f.rfidEpc ?? ''} onChange={(e) => set('rfidEpc', e.target.value)} /></Field>
          <Field label="Location / shelf"><input value={f.location ?? ''} onChange={(e) => set('location', e.target.value)} /></Field>
          <Field label="Status">
            <select value={f.status} disabled={circulating} onChange={(e) => set('status', e.target.value as Item['status'])}>
              {ITEM_STATUSES.filter((s) => circulating || (s !== 'ON_LOAN' && s !== 'ON_HOLD'))
                .map((s) => <option key={s} value={s}>{label(s)}</option>)}
            </select>
          </Field>
          <Field label="Date acquired"><input type="date" value={f.acquiredDate ?? ''} onChange={(e) => set('acquiredDate', e.target.value)} /></Field>
          <Field label="Price (PHP)"><input type="number" step="0.01" min="0" inputMode="decimal" value={f.price ?? ''}
            onChange={(e) => set('price', e.target.value ? Number(e.target.value) : null)} /></Field>
          <Field label="Notes" className="span-all"><input value={f.notes ?? ''} onChange={(e) => set('notes', e.target.value)} /></Field>
        </div>
      </div>
    </Modal>
  )
}

import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, openPdf, qs } from '../../api/client'
import { ITEM_STATUSES, MATERIAL_TYPES, type BibRecord, type CustomFieldDef, type IsbnResult, type Item, type Loan, type Page, type RecordDetail } from '../../api/types'
import { HeadingInput } from '../../components/HeadingInput'
import { useAsync } from '../../hooks/useAsync'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Pager, StatusBadge, Table } from '../../components/ui'
import { fmtDate, fmtDateTime, label } from '../../format'
import { CameraScanButton } from '../../components/CameraScan'
import { CopyCatalogModal } from '../../components/CopyCatalogModal'
import { DigitalPanel } from '../../components/DigitalPanel'
import { Icon } from '../../components/Icon'

type RecordForm = Omit<BibRecord, 'id' | 'totalCopies' | 'availableCopies' | 'createdAt' | 'updatedAt' | 'ebook'>

const EMPTY: RecordForm = {
  title: '', subtitle: '', authors: '', isbn: '', publisher: '', publicationYear: null, edition: '', subjects: '',
  callNumber: '', materialType: 'BOOK', language: 'English', summary: '', customFields: {},
}

export function RecordPage() {
  const { id } = useParams()
  const isNew = !id
  const navigate = useNavigate()
  const toast = useToast()
  const canDelete = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  const [form, setForm] = useState<RecordForm>(EMPTY)
  const [items, setItems] = useState<Item[]>([])
  const [onLoan, setOnLoan] = useState<Map<number, Loan>>(new Map())
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [editingItem, setEditingItem] = useState<Partial<Item> | null>(null)
  const [copycat, setCopycat] = useState(false)
  const { data: fieldDefs } = useAsync(() => api<CustomFieldDef[]>('/api/custom-fields'), [])

  async function load() {
    if (isNew) return
    try {
      const d = await api<RecordDetail>(`/api/catalog/records/${id}`)
      setForm(Object.fromEntries(Object.keys(EMPTY).map((k) => [k, d.record[k as keyof RecordForm]])) as RecordForm)
      setItems(d.items)
      // Current borrower of each copy (current loans come first in the title's loan list).
      const loans = await api<Page<Loan>>(`/api/circulation/records/${id}/loans?size=100`)
      setOnLoan(new Map(loans.content.filter((l) => !l.returnedAt).map((l) => [l.itemId, l])))
    } catch (e) {
      setError(errorText(e))
    }
  }
  useEffect(() => { void load() }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  const set = <K extends keyof RecordForm>(k: K, v: RecordForm[K]) => setForm((f) => ({ ...f, [k]: v }))

  async function lookupIsbn() {
    if (!form.isbn) return toast('Enter an ISBN first', 'warn')
    if (!isIsbn(form.isbn)) {
      return toast(`${form.isbn.trim()} is not an ISBN (an ISBN has 10 or 13 digits, usually starting with 978 or 979). `
        + 'Store or product barcodes can\'t be looked up: type the details in, or try LoC by title.', 'warn')
    }
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
          <Field label="ISBN" className="span-all" hint="Scan or type, then press Lookup (Open Library) or LoC (Library of Congress) to copy-catalog">
            <div className="input-group">
              <input value={form.isbn ?? ''} onChange={(e) => set('isbn', e.target.value)} />
              <CameraScanButton title="Scan the ISBN barcode" onScan={(t) => set('isbn', t)} />
              <button type="button" className="btn" onClick={lookupIsbn}>Lookup</button>
              <button type="button" className="btn" onClick={() => setCopycat(true)} title="Search the Library of Congress catalog">LoC</button>
            </div>
          </Field>
          <Field label="Material type">
            <select value={form.materialType} onChange={(e) => set('materialType', e.target.value as RecordForm['materialType'])}>
              {MATERIAL_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}
            </select>
          </Field>
          <Field label="Title *" className="span-all"><input value={form.title} onChange={(e) => set('title', e.target.value)} required /></Field>
          <Field label="Subtitle" className="span-all"><input value={form.subtitle ?? ''} onChange={(e) => set('subtitle', e.target.value)} /></Field>
          <Field label="Authors" hint="Separate with ; — suggestions come from the authority list" className="span-all" as="div">
            <HeadingInput type="AUTHOR" value={form.authors ?? ''} onChange={(v) => set('authors', v)} /></Field>
          <Field label="Publisher"><input value={form.publisher ?? ''} onChange={(e) => set('publisher', e.target.value)} /></Field>
          <Field label="Year"><input type="number" inputMode="numeric" value={form.publicationYear ?? ''}
            onChange={(e) => set('publicationYear', e.target.value ? Number(e.target.value) : null)} /></Field>
          <Field label="Edition"><input value={form.edition ?? ''} onChange={(e) => set('edition', e.target.value)} /></Field>
          <Field label="Call number"><input value={form.callNumber ?? ''} onChange={(e) => set('callNumber', e.target.value)} /></Field>
          <Field label="Language"><input value={form.language ?? ''} onChange={(e) => set('language', e.target.value)} /></Field>
          <Field label="Subjects" hint="Separate with ; — suggestions come from the authority list" className="span-all" as="div">
            <HeadingInput type="SUBJECT" value={form.subjects ?? ''} onChange={(v) => set('subjects', v)} /></Field>
          {fieldDefs?.map((d) => {
            const v = form.customFields?.[d.key] ?? ''
            const setV = (val: string) => set('customFields', { ...(form.customFields ?? {}), [d.key]: val })
            return (
              <Field key={d.key} label={d.label} hint={d.showInOpac ? undefined : 'Staff only'}>
                {d.type === 'SELECT'
                  ? <select value={v} onChange={(e) => setV(e.target.value)}><option value="">—</option>{d.options.map((o) => <option key={o}>{o}</option>)}</select>
                  : <input type={d.type === 'NUMBER' ? 'number' : d.type === 'DATE' ? 'date' : 'text'} value={v} onChange={(e) => setV(e.target.value)} />}
              </Field>
            )
          })}
          <Field label="Summary / abstract" className="span-all"><textarea value={form.summary ?? ''} onChange={(e) => set('summary', e.target.value)} /></Field>
        </div>
        <div className="row end actions-stretch"><button className="btn primary" disabled={busy}>{isNew ? 'Create record' : 'Save changes'}</button></div>
      </form>

      {!isNew && <DigitalPanel recordId={Number(id)} />}

      {!isNew && (
        <div className="card">
          <div className="page-head" style={{ marginBottom: 8 }}>
            <h2 style={{ margin: 0 }}>Copies ({items.length})</h2>
            <div className="row">
              {items.length > 0 && <select className="btn" style={{ width: 'auto' }} value="" aria-label="Print labels" onChange={(e) => {
                const kind = e.target.value
                openPdf(`/api/labels/items${qs({ recordId: id, kind })}`).catch((err) => toast(errorText(err), 'error'))
              }}>
                <option value="" disabled>Print labels…</option>
                <option value="barcode">Barcode labels (all copies)</option>
                <option value="spine">Spine labels (all copies)</option>
              </select>}
              <button className="btn primary" onClick={() => setEditingItem({ status: 'AVAILABLE', location: 'General Circulation' })}><Icon name="plus" />Add copy</button>
            </div>
          </div>
          <Table rows={items} rowKey={(i) => i.id} onRowClick={setEditingItem} empty="No copies yet" columns={[
            { header: 'Barcode', render: (i) => <span className="mono">{i.barcode}</span> },
            { header: 'RFID EPC', render: (i) => <span className="mono small">{i.rfidEpc ?? '—'}</span> },
            { header: 'Shelf location', render: (i) => i.shelfLocation ?? <span className="muted">Not set</span> },
            { header: 'Loan', render: (i) => i.roomUseOnly ? <span className="badge warn">Room use only</span> : <span className="muted small">Can be borrowed</span> },
            { header: 'Status', render: (i) => <><StatusBadge status={i.status} />{i.dueAt && <span className="small muted"> due {fmtDate(i.dueAt)}</span>}</> },
            { header: 'Borrowed by', render: (i) => {
              const l = onLoan.get(i.id)
              return l ? <PatronLink loan={l} /> : <span className="muted">—</span>
            } },
          ]} />
        </div>
      )}

      {!isNew && <BorrowerHistory recordId={Number(id)} />}
      {copycat && <CopyCatalogModal initialIsbn={form.isbn ?? ''} initialTitle={form.title} onClose={() => setCopycat(false)}
        onUse={(c) => {
          setForm((f) => ({
            ...f, title: c.title ?? f.title, subtitle: c.subtitle ?? f.subtitle, authors: c.authors ?? f.authors, isbn: c.isbn ?? f.isbn,
            publisher: c.publisher ?? f.publisher, publicationYear: c.publicationYear ?? f.publicationYear, edition: c.edition ?? f.edition,
            subjects: c.subjects ?? f.subjects, callNumber: c.callNumber ?? f.callNumber, materialType: c.materialType ?? f.materialType,
            language: c.language ?? f.language, summary: c.summary ?? f.summary,
          }))
          setCopycat(false)
          toast('Filled from the Library of Congress record — review and save')
        }} />}
      {editingItem && <ItemModal recordId={Number(id)} item={editingItem} canDelete={canDelete} others={items.filter((x) => x.id !== editingItem.id)}
        onClose={() => setEditingItem(null)} onSaved={() => { setEditingItem(null); void load() }} />}
    </>
  )
}

/** Patron name and card number, linking to the patron's page (stopping the row's own click). */
function PatronLink({ loan }: { loan: Loan }) {
  return <Link to={`/staff/patrons/${loan.patronId}`} onClick={(e) => e.stopPropagation()}>
    {loan.patronName}<div className="muted small mono">{loan.cardNumber}</div></Link>
}

/** Everyone who has borrowed a copy of this title, current borrowers first. */
function BorrowerHistory({ recordId }: { recordId: number }) {
  const [page, setPage] = useState(0)
  const { data, error } = useAsync(() => api<Page<Loan>>(`/api/circulation/records/${recordId}/loans${qs({ page })}`), [recordId, page])
  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>Borrowing history{data ? ` (${data.totalElements})` : ''}</h2>
      <ErrorAlert error={error} />
      <Table rows={data?.content} rowKey={(l) => l.id} empty="No one has borrowed this title yet" columns={[
        { header: 'Patron', render: (l) => <PatronLink loan={l} /> },
        { header: 'Copy', render: (l) => <span className="mono">{l.barcode}</span> },
        { header: 'Borrowed', render: (l) => <>{fmtDateTime(l.checkoutAt)}{l.checkedOutBy && <div className="muted small">by {l.checkedOutBy}</div>}</> },
        { header: 'Due', render: (l) => fmtDate(l.dueAt) },
        { header: 'Returned', render: (l) => l.returnedAt ? fmtDateTime(l.returnedAt)
          : <span className={`badge ${l.overdue ? 'bad' : 'ok'}`}>{l.overdue ? `Overdue ${l.overdueDays}d` : 'On loan'}</span> },
      ]} />
      <Pager page={data} onPage={setPage} />
    </div>
  )
}

/** ISBN-10 or ISBN-13 with a valid check digit (hyphens and spaces ignored). */
function isIsbn(value: string) {
  const v = value.replace(/[\s-]/g, '').toUpperCase()
  if (/^\d{9}[\dX]$/.test(v)) {
    const sum = [...v].reduce((t, c, i) => t + (c === 'X' ? 10 : Number(c)) * (10 - i), 0)
    return sum % 11 === 0
  }
  if (/^97[89]\d{10}$/.test(v)) {
    const sum = [...v].reduce((t, c, i) => t + Number(c) * (i % 2 ? 3 : 1), 0)
    return sum % 10 === 0
  }
  return false
}

const SECTIONS = ['General Circulation', 'Fiction Section', 'Filipiniana', 'Reference Section', 'Periodicals Section',
  'Thesis & Research Section', 'Audio-Visual Room', 'Special Collections']

function itemBody(i: Partial<Item>) {
  return { barcode: i.barcode, rfidEpc: i.rfidEpc, location: i.location, aisle: i.aisle, shelf: i.shelf,
    roomUseOnly: i.roomUseOverride ?? null, status: i.status,
    acquiredDate: i.acquiredDate || null, price: i.price ?? null, notes: i.notes }
}

function ItemModal({ recordId, item, canDelete, others, onClose, onSaved }: {
  recordId: number; item: Partial<Item>; canDelete: boolean; others: Item[]; onClose: () => void; onSaved: () => void
}) {
  const [f, setF] = useState<Partial<Item>>(item)
  const [applyAll, setApplyAll] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  const set = <K extends keyof Item>(k: K, v: Item[K] | null) => setF((x) => ({ ...x, [k]: v }))
  const circulating = f.status === 'ON_LOAN' || f.status === 'ON_HOLD'

  async function save() {
    setError(null)
    const body = itemBody(f)
    try {
      if (item.id) await api(`/api/catalog/items/${item.id}`, { method: 'PUT', body })
      else await api(`/api/catalog/records/${recordId}/items`, { body })
      if (applyAll) {
        // Same shelf for every copy of this title; other fields of those copies are kept as they are.
        for (const o of others) {
          await api(`/api/catalog/items/${o.id}`, { method: 'PUT', body: { ...itemBody(o), location: f.location, aisle: f.aisle, shelf: f.shelf } })
        }
      }
      toast(applyAll && others.length ? `Location saved for all ${others.length + 1} copies` : 'Copy saved')
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
          <Field label="Barcode *"><div className="input-group">
            <input className="mono" value={f.barcode ?? ''} onChange={(e) => set('barcode', e.target.value)} autoFocus />
            <CameraScanButton onScan={(t) => set('barcode', t)} /></div></Field>
          <Field label="RFID tag EPC" hint="Read with the tagging device"><input className="mono" value={f.rfidEpc ?? ''} onChange={(e) => set('rfidEpc', e.target.value)} /></Field>
          <Field label="Section / area" hint="Pick or type a section">
            <input list="sections" value={f.location ?? ''} onChange={(e) => set('location', e.target.value)} />
            <datalist id="sections">{SECTIONS.map((s) => <option key={s} value={s} />)}</datalist>
          </Field>
          <Field label="Loan policy" hint={f.roomUseOverride == null ? `Default for this material type: ${item.roomUseOnly && item.roomUseOverride == null ? 'room use only' : 'can be borrowed'}` : 'Set for this copy'}>
            <select value={f.roomUseOverride == null ? '' : String(f.roomUseOverride)}
              onChange={(e) => set('roomUseOverride', e.target.value === '' ? null : e.target.value === 'true')}>
              <option value="">Follow material type</option>
              <option value="true">Room use only</option>
              <option value="false">Can be borrowed</option>
            </select>
          </Field>
          <Field label="Aisle" hint="e.g. 1, 3A"><input value={f.aisle ?? ''} maxLength={20} onChange={(e) => set('aisle', e.target.value)} /></Field>
          <Field label="Shelf" hint="e.g. 2, top"><input value={f.shelf ?? ''} maxLength={20} onChange={(e) => set('shelf', e.target.value)} /></Field>
          {others.length > 0 && (
            <label className="check span-all"><input type="checkbox" checked={applyAll} onChange={(e) => setApplyAll(e.target.checked)} />
              Use this section, aisle and shelf for all {others.length + 1} copies of this title</label>
          )}
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

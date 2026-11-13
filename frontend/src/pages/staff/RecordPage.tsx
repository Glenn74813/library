import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, openPdf, qs } from '../../api/client'
import { ITEM_STATUSES, MATERIAL_TYPES, type BibRecord, type CustomFieldDef, type IsbnResult, type Item, type LibrarySection, type Loan, type Page, type RecordDetail } from '../../api/types'
import { HeadingInput } from '../../components/HeadingInput'
import { useAsync } from '../../hooks/useAsync'
import { useDebounced } from '../../hooks/useDebounced'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Pager, StatusBadge, Table } from '../../components/ui'
import { fmtDate, fmtDateTime, label, shelvesLabel } from '../../format'
import { CameraScanButton } from '../../components/CameraScan'
import { CopyCatalogModal } from '../../components/CopyCatalogModal'
import { DigitalPanel } from '../../components/DigitalPanel'
import { getStation, station } from '../../api/rfidStation'
import { Icon } from '../../components/Icon'

type RecordForm = Omit<BibRecord, 'id' | 'totalCopies' | 'availableCopies' | 'createdAt' | 'updatedAt' | 'ebook'>

const EMPTY: RecordForm = {
  title: '', subtitle: '', authors: '', isbn: '', publisher: '', publicationYear: null, edition: '', subjects: '',
  callNumber: '', materialType: 'BOOK', language: 'English', summary: '', customFields: {},
}

/**
 * A catalog title. Opened from the catalog list it shows the record's details, its copies and who borrowed
 * them; editing the bibliographic data is its own page (/edit), as is a new record.
 */
export function RecordPage({ edit = false }: { edit?: boolean }) {
  const { id } = useParams()
  const isNew = !id
  const editing = isNew || edit
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const toast = useToast()
  const session = useSession()
  const canDelete = hasRole(session, 'ADMIN', 'LIBRARIAN')
  // Deleting a whole catalog record is for librarians only.
  const canDeleteRecord = hasRole(session, 'LIBRARIAN')
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

  // "Add copy" from the catalog list, or right after creating a record: open the copy form.
  useEffect(() => {
    if (!editing && params.get('addCopy')) {
      setEditingItem(NEW_COPY)
      setParams({}, { replace: true })
    }
  }, [editing, params, setParams])

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

  // Another record with this ISBN (the same edition): copies belong there, under one call number.
  const isbnTyped = useDebounced((form.isbn ?? '').replace(/[^0-9Xx]/g, ''))
  const { data: sameIsbn } = useAsync(() => isbnTyped.length === 10 || isbnTyped.length === 13
    ? api<{ id: number; title: string; isbn: string; callNumber: string | null; copies: number }[]>(
      `/api/catalog/records/same-isbn${qs({ isbn: isbnTyped, exclude: isNew ? undefined : id })}`)
    : Promise.resolve([]), [isbnTyped, id])

  async function save(e: FormEvent) {
    e.preventDefault()
    if (isNew && sameIsbn?.length && !confirm(`The ISBN ${form.isbn} is already in the catalog as "${sameIsbn[0].title}". `
      + 'For another copy of the same book, cancel and press Add copy on that record.\n\nCreate a separate record anyway?')) return
    setBusy(true)
    setError(null)
    try {
      if (isNew) {
        const r = await api<BibRecord>('/api/catalog/records', { body: form })
        toast('Record created — now add its copies')
        navigate(`/staff/catalog/${r.id}?addCopy=1`, { replace: true })
      } else {
        await api(`/api/catalog/records/${id}`, { method: 'PUT', body: form })
        toast('Record saved')
        navigate(`/staff/catalog/${id}`)
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
        <h1>{isNew ? 'New catalog record' : edit ? `Edit: ${form.title || 'record'}` : form.title || 'Record'}</h1>
        <div className="row">
          <button className="btn" onClick={() => navigate(edit ? `/staff/catalog/${id}` : '/staff/catalog')}><Icon name="chevron-left" />Back</button>
          {!editing && <button className="btn" onClick={() => setEditingItem(NEW_COPY)}><Icon name="plus" />Add copy</button>}
          {!editing && <button className="btn" onClick={() => navigate(`/staff/catalog/${id}/edit`)}><Icon name="edit-2" />Edit</button>}
          {!isNew && canDeleteRecord && <button className="btn danger" onClick={remove}><Icon name="trash-2" />Delete</button>}
        </div>
      </div>
      {!editing && <RecordSummary form={form} fieldDefs={fieldDefs ?? []} error={error} />}
      {editing && <form className="card stack" onSubmit={save}>
        <ErrorAlert error={error} />
        <div className="form-grid">
          <Field label="ISBN" className="span-all" hint="Scan or type, then press Lookup (Open Library) or Copy-catalog (Library of Congress or National Library of the Philippines)">
            <div className="input-group">
              <input value={form.isbn ?? ''} onChange={(e) => set('isbn', e.target.value)} />
              <CameraScanButton title="Scan the ISBN barcode" onScan={(t) => set('isbn', t)} />
              <button type="button" className="btn" onClick={lookupIsbn}>Lookup</button>
              <button type="button" className="btn" onClick={() => setCopycat(true)} title="Search the Library of Congress or the National Library of the Philippines">Copy-catalog</button>
            </div>
          </Field>
          {sameIsbn && sameIsbn.length > 0 && <div className="alert warn span-all">
            <strong>This ISBN is already in the catalog.</strong> For another copy of the same book, open it and press <strong>Add copy</strong>, so
            all copies share one record and one call number.
            <ul style={{ margin: '6px 0 0' }}>{sameIsbn.map((r) => <li key={r.id}>
              <Link to={`/staff/catalog/${r.id}`}>{r.title}</Link> · ISBN {r.isbn}{r.callNumber && <> · call no. <span className="mono">{r.callNumber}</span></>}
              {' '}· {r.copies} cop{r.copies === 1 ? 'y' : 'ies'}</li>)}</ul>
          </div>}
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
      </form>}

      {edit && <DigitalPanel recordId={Number(id)} />}

      {!editing && (
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
                <option value="spine">Spine labels with shelf address (all copies)</option>
              </select>}
              <button className="btn primary" onClick={() => setEditingItem(NEW_COPY)}><Icon name="plus" />Add copy</button>
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

      {!editing && <BorrowerHistory recordId={Number(id)} />}
      {copycat && <CopyCatalogModal initialIsbn={form.isbn ?? ''} initialTitle={form.title} onClose={() => setCopycat(false)}
        onUse={(c) => {
          // The ISBN already typed or scanned is the book in hand; a record found by title may be another
          // edition (e.g. the hardcover of this paperback), so its ISBN only fills an empty field.
          const own = form.isbn?.trim()
          const otherEdition = !!own && !!c.isbn && own.replace(/[^0-9Xx]/g, '') !== c.isbn.replace(/[^0-9Xx]/g, '')
          setForm((f) => ({
            ...f, title: c.title ?? f.title, subtitle: c.subtitle ?? f.subtitle, authors: c.authors ?? f.authors, isbn: f.isbn?.trim() ? f.isbn : c.isbn ?? f.isbn,
            publisher: c.publisher ?? f.publisher, publicationYear: c.publicationYear ?? f.publicationYear, edition: c.edition ?? f.edition,
            subjects: c.subjects ?? f.subjects, callNumber: c.callNumber ?? f.callNumber, materialType: c.materialType ?? f.materialType,
            language: c.language ?? f.language, summary: c.summary ?? f.summary,
          }))
          setCopycat(false)
          toast(otherEdition
            ? `Filled from the ${c.source} record of another edition (ISBN ${c.isbn}); your ISBN was kept. Check the year, publisher and edition, then save.`
            : `Filled from the ${c.source} record — review and save`)
        }} />}
      {editingItem && <ItemModal recordId={Number(id)} item={editingItem} canDelete={canDelete} others={items.filter((x) => x.id !== editingItem.id)}
        onClose={() => setEditingItem(null)} onSaved={() => { setEditingItem(null); void load() }} />}
    </>
  )
}

const NEW_COPY: Partial<Item> = { status: 'AVAILABLE' }

/** The record's details, read-only (Edit opens the form). */
function RecordSummary({ form, fieldDefs, error }: { form: RecordForm; fieldDefs: CustomFieldDef[]; error: string | null }) {
  const rows: [string, React.ReactNode][] = [
    ['Authors', form.authors], ['ISBN', form.isbn && <span className="mono">{form.isbn}</span>],
    ['Call number', form.callNumber && <span className="mono">{form.callNumber}</span>], ['Material type', label(form.materialType)],
    ['Publisher', form.publisher], ['Year', form.publicationYear], ['Edition', form.edition], ['Language', form.language],
    ['Subjects', form.subjects],
    ...fieldDefs.map((d): [string, React.ReactNode] => [d.label, form.customFields?.[d.key]]),
  ]
  return (
    <div className="card stack">
      <ErrorAlert error={error} />
      {form.subtitle && <div className="muted" style={{ marginTop: -4 }}>{form.subtitle}</div>}
      <dl className="record-details">
        {rows.filter(([, v]) => v !== null && v !== undefined && v !== '').map(([k, v]) => (
          <div key={k}><dt>{k}</dt><dd>{v}</dd></div>
        ))}
      </dl>
      {form.summary && <p style={{ margin: 0, whiteSpace: 'pre-line' }}>{form.summary}</p>}
    </div>
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

function itemBody(i: Partial<Item>) {
  return { barcode: i.barcode, rfidEpc: i.rfidEpc, location: i.location, aisle: i.aisle, shelf: i.shelf,
    roomUseOnly: i.roomUseOverride ?? null, status: i.status,
    acquiredDate: i.acquiredDate || null, price: i.price ?? null, notes: i.notes }
}

/** Add or edit one copy (also opened from the catalog list's Add copy button, with that title in {@code title}). */
export function ItemModal({ recordId, item, canDelete, others, title, onClose, onSaved }: {
  recordId: number; item: Partial<Item>; canDelete: boolean; others: Item[]; title?: string; onClose: () => void; onSaved: () => void
}) {
  const [f, setF] = useState<Partial<Item>>(item)
  const [applyAll, setApplyAll] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  const set = <K extends keyof Item>(k: K, v: Item[K] | null) => setF((x) => ({ ...x, [k]: v }))
  const circulating = f.status === 'ON_LOAN' || f.status === 'ON_HOLD'
  // RFID station on this computer (rfid-bridge): read the tag on the pad, or write the copy's barcode to it.
  const useStation = getStation().enabled
  const [tagBusy, setTagBusy] = useState(false)
  async function readTag() {
    setTagBusy(true)
    try {
      const tags = await station.tags()
      if (tags.length !== 1) setError(tags.length ? `${tags.length} tags are on the pad: leave only this copy's tag` : 'No tag on the pad')
      else { set('rfidEpc', tags[0]); setError(null) }
    } catch (e) { setError(errorText(e)) } finally { setTagBusy(false) }
  }
  async function writeTag() {
    setTagBusy(true)
    try {
      set('rfidEpc', await station.write(f.barcode!.trim()))
      setError(null)
      toast('Tag written; save the copy to keep its RFID number')
    } catch (e) { setError(errorText(e)) } finally { setTagBusy(false) }
  }
  const { data: sections } = useAsync(() => api<LibrarySection[]>('/api/sections'), [])
  const isAdmin = hasRole(useSession(), 'ADMIN')
  // Aisle and shelf choices come from the section's layout (Administration → Sections & Aisles).
  const section = sections?.find((s) => s.name === f.location)
  const aisle = section?.aisles.find((a) => a.name.toLowerCase() === (f.aisle ?? '').toLowerCase())

  async function save() {
    setError(null)
    const body = itemBody(f)
    try {
      const saved = item.id ? await api<Item>(`/api/catalog/items/${item.id}`, { method: 'PUT', body })
        : await api<Item>(`/api/catalog/records/${recordId}/items`, { body })
      if (applyAll) {
        // Same shelf for every copy of this title; other fields of those copies are kept as they are.
        for (const o of others) {
          await api(`/api/catalog/items/${o.id}`, { method: 'PUT', body: { ...itemBody(o), location: f.location, aisle: f.aisle, shelf: f.shelf } })
        }
      }
      toast(!item.id && !f.barcode?.trim()
        ? `Copy added with barcode ${saved.barcode}. Print its barcode label (Print labels…) and stick it on the book.`
        : applyAll && others.length ? `Location saved for all ${others.length + 1} copies` : 'Copy saved')
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
    <Modal title={item.id ? `Copy ${item.barcode}` : title ?? 'Add copy'} onClose={onClose} footer={<>
      {item.id && canDelete && <button className="btn danger" onClick={remove}>Delete</button>}
      <span className="spacer" />
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn primary" onClick={save}>Save</button>
    </>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <div className="form-grid">
          <Field label={item.id ? 'Barcode *' : 'Barcode'} as="div" hint={item.id ? 'The library label on this copy'
            : 'Leave empty to assign the next library barcode, or scan a pre-printed library label'}><div className="input-group">
            <input className="mono" value={f.barcode ?? ''} onChange={(e) => set('barcode', e.target.value)} autoFocus
              placeholder={item.id ? undefined : 'Assigned when saved'} />
            <CameraScanButton onScan={(t) => set('barcode', t)} /></div></Field>
          <Field label="RFID tag EPC" as="div" hint={useStation ? 'Put the tag on the RFID pad, then Read (existing tag) or Write (new tag from the barcode)'
            : 'Read with the tagging device'}>
            <div className="input-group">
              <input className="mono" value={f.rfidEpc ?? ''} onChange={(e) => set('rfidEpc', e.target.value)} />
              {useStation && <>
                <button type="button" className="btn" disabled={tagBusy} onClick={() => void readTag()}>Read</button>
                <button type="button" className="btn" disabled={tagBusy || !f.barcode?.trim()} title={f.barcode?.trim() ? undefined : 'Save the copy first to get its barcode'}
                  onClick={() => void writeTag()}>Write</button>
              </>}
            </div>
          </Field>
          <Field label="Section / area" as="div" hint={isAdmin ? <>Set up under <Link to="/staff/sections">Sections &amp; Aisles</Link></>
            : 'Sections and aisles are set up by an administrator'}>
            <select value={f.location ?? ''} onChange={(e) => setF((x) => ({ ...x, location: e.target.value || null, aisle: null, shelf: null }))}>
              <option value="">Not set</option>
              {sections?.map((s) => <option key={s.id} value={s.name}>{s.name}</option>)}
              {/* A section no longer on the list stays visible until it is changed. */}
              {f.location && sections && !sections.some((s) => s.name === f.location) && <option value={f.location}>{f.location} (not on the list)</option>}
            </select>
          </Field>
          <Field label="Loan policy" hint={f.roomUseOverride == null ? `Default for this material type: ${item.roomUseOnly && item.roomUseOverride == null ? 'room use only' : 'can be borrowed'}` : 'Set for this copy'}>
            <select value={f.roomUseOverride == null ? '' : String(f.roomUseOverride)}
              onChange={(e) => set('roomUseOverride', e.target.value === '' ? null : e.target.value === 'true')}>
              <option value="">Follow material type</option>
              <option value="true">Room use only</option>
              <option value="false">Can be borrowed</option>
            </select>
          </Field>
          <Field label="Aisle" hint={f.location && !section?.aisles.length ? 'This section has no aisles yet' : undefined}>
            <select value={f.aisle ?? ''} disabled={!section} onChange={(e) => setF((x) => ({ ...x, aisle: e.target.value || null, shelf: null }))}>
              <option value="">{section ? 'Not set' : 'Choose a section first'}</option>
              {section?.aisles.map((a) => <option key={a.id} value={a.name}>Aisle {a.name}</option>)}
              {f.aisle && section && !aisle && <option value={f.aisle}>{f.aisle} (not on the list)</option>}
            </select>
          </Field>
          <Field label="Shelf" hint={aisle ? `Aisle ${aisle.name} has ${shelvesLabel(aisle.shelves)}` : undefined}>
            <select value={f.shelf ?? ''} disabled={!aisle} onChange={(e) => set('shelf', e.target.value || null)}>
              <option value="">{aisle ? 'Not set' : 'Choose an aisle first'}</option>
              {aisle && Array.from({ length: aisle.shelves }, (_, n) => String(n + 1)).map((n) => <option key={n} value={n}>Shelf {n}</option>)}
              {f.shelf && aisle && !(Number(f.shelf) >= 1 && Number(f.shelf) <= aisle.shelves && /^\d+$/.test(f.shelf))
                && <option value={f.shelf}>{f.shelf} (not on the list)</option>}
            </select>
          </Field>
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

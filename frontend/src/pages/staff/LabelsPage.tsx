import { useState } from 'react'
import { api, openPdf, qs } from '../../api/client'
import type { Category, Course } from '../../api/types'
import { Icon } from '../../components/Icon'
import { errorText, useToast } from '../../components/Toast'
import { Field } from '../../components/ui'
import { useAsync } from '../../hooks/useAsync'
import { HelpButton } from '../../components/HelpButton'

/** Print item barcode / spine labels and patron ID cards on A4 label sheets. */
export function LabelsPage() {
  return (
    <>
      <div className="page-head"><h1>Labels & library IDs<HelpButton /></h1></div>
      <p className="muted" style={{ marginTop: -8 }}>PDFs open in a new tab; print at <strong>100% / Actual size</strong> (not “Fit to page”) so they line up with the label sheet.</p>
      <div className="grid cols-2" style={{ alignItems: 'start' }}>
        <ItemLabels />
        <PatronCards />
      </div>
    </>
  )
}

function ItemLabels() {
  const toast = useToast()
  const [barcodes, setBarcodes] = useState('')
  const [addedSince, setAddedSince] = useState('')
  const [kind, setKind] = useState<'barcode' | 'spine'>('barcode')
  const [skip, setSkip] = useState(0)
  async function print() {
    try {
      await openPdf(`/api/labels/items${qs({ barcodes: barcodes.trim() || undefined, addedSince: addedSince || undefined, kind, skip })}`)
    } catch (e) { toast(errorText(e), 'error') }
  }
  return (
    <div className="card stack">
      <h2 style={{ margin: 0 }}><Icon name="tag" /> Item labels</h2>
      <div className="form-grid">
        <Field label="Label type" className="span-all">
          <select value={kind} onChange={(e) => setKind(e.target.value as 'barcode' | 'spine')}>
            <option value="barcode">Barcode labels — Avery L7160 / J8160 (21 per A4 sheet)</option>
            <option value="spine">Spine labels (shelf address + call number) — Avery L7651 (65 per A4 sheet)</option>
          </select>
        </Field>
        <Field label="Barcodes" className="span-all" hint="Scan or type, one per line or separated by commas">
          <textarea rows={6} value={barcodes} onChange={(e) => setBarcodes(e.target.value)} className="mono" /></Field>
        <Field label="…or copies added since" hint="e.g. today, for a batch of new books"><input type="date" value={addedSince} onChange={(e) => setAddedSince(e.target.value)} /></Field>
        <Field label="Skip labels" hint="Already used labels at the top of the sheet"><input type="number" min={0} max={64} value={skip} onChange={(e) => setSkip(Number(e.target.value))} /></Field>
      </div>
      <div className="row end"><button className="btn primary" onClick={print}><Icon name="printer" />Print labels</button></div>
    </div>
  )
}

function PatronCards() {
  const toast = useToast()
  const { data: categories } = useAsync(() => api<Category[]>('/api/patron-categories'), [])
  const { data: courses } = useAsync(() => api<Course[]>('/api/courses'), [])
  const [cards, setCards] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [courseId, setCourseId] = useState('')
  const [since, setSince] = useState('')
  const [skip, setSkip] = useState(0)
  const [layout, setLayout] = useState<'card' | 'sheet'>('card')
  async function print() {
    try {
      await openPdf(`/api/labels/patron-cards${qs({ cards: cards.trim() || undefined, categoryId: categoryId || undefined,
        courseId: courseId || undefined, registeredSince: since || undefined, layout, skip: layout === 'sheet' ? skip : undefined })}`)
    } catch (e) { toast(errorText(e), 'error') }
  }
  return (
    <div className="card stack">
      <h2 style={{ margin: 0 }}><Icon name="credit-card" /> Library IDs</h2>
      <p className="muted small" style={{ margin: 0 }}>Standard CR80 ID card size (85.6 × 54 mm, like a bank card), with photo,
        ID number and barcode. Patrons without a photo get an empty frame.</p>
      <div className="form-grid">
        <Field label="ID numbers" className="span-all" hint="One per line or separated by commas; leave empty to use the filters">
          <textarea rows={4} value={cards} onChange={(e) => setCards(e.target.value)} className="mono" /></Field>
        <Field label="Category">
          <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            <option value="">All</option>{categories?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select></Field>
        <Field label="Program">
          <select value={courseId} onChange={(e) => setCourseId(e.target.value)}>
            <option value="">All</option>{courses?.map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}
          </select></Field>
        <Field label="Registered since"><input type="date" value={since} onChange={(e) => setSince(e.target.value)} /></Field>
        <Field label="Print on" className="span-all" hint={layout === 'card'
          ? 'Each page is one card, for a PVC ID card printer. Choose the CR80 card size in the printer dialog.'
          : 'For card stock or sticker paper: cut along the outlines, then laminate.'}>
          <select value={layout} onChange={(e) => setLayout(e.target.value as 'card' | 'sheet')}>
            <option value="card">ID card printer: one CR80 card per page</option>
            <option value="sheet">A4 paper: 10 IDs per sheet to cut out</option>
          </select></Field>
        {layout === 'sheet' && <Field label="Skip IDs" hint="Already used spots on the sheet"><input type="number" min={0} max={9} value={skip} onChange={(e) => setSkip(Number(e.target.value))} /></Field>}
      </div>
      <div className="row end"><button className="btn primary" onClick={print}><Icon name="printer" />Print library IDs</button></div>
    </div>
  )
}

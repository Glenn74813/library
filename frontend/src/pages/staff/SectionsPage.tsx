import { useState, type FormEvent } from 'react'
import { api } from '../../api/client'
import type { LibraryAisle, LibrarySection } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { Icon } from '../../components/Icon'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Table } from '../../components/ui'
import { useAsync } from '../../hooks/useAsync'
import { shelvesLabel } from '../../format'

/**
 * Administration → Sections & Aisles: the library's layout. A copy's section, aisle and shelf, and a
 * stocktake's scope, are picked from it, so names always match and shelves stay within each aisle.
 */
export function SectionsPage() {
  const { data, error, reload } = useAsync(() => api<LibrarySection[]>('/api/sections'), [])
  const isAdmin = hasRole(useSession(), 'ADMIN')
  const [name, setName] = useState('')
  const [editing, setEditing] = useState<LibrarySection | null>(null)
  const toast = useToast()

  async function add(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) return
    try {
      await api('/api/sections', { body: { name } })
      toast(`Added ${name.trim()}`)
      setName('')
      void reload()
    } catch (err) { toast(errorText(err), 'error') }
  }

  /** Swaps a section with its neighbour in the list. */
  async function move(i: number, by: -1 | 1) {
    const list = data ?? []
    const a = list[i], b = list[i + by]
    if (!a || !b) return
    try {
      await api(`/api/sections/${a.id}`, { method: 'PUT', body: { name: a.name, sortOrder: b.sortOrder === a.sortOrder ? a.sortOrder + by : b.sortOrder } })
      await api(`/api/sections/${b.id}`, { method: 'PUT', body: { name: b.name, sortOrder: a.sortOrder } })
      void reload()
    } catch (err) { toast(errorText(err), 'error') }
  }

  // Keep the open section in step with the reloaded list.
  const open = editing && data?.find((s) => s.id === editing.id)

  return (
    <>
      <div className="page-head"><h1>Sections &amp; Aisles</h1></div>
      <p className="muted" style={{ marginTop: -8 }}>The library's layout: sections, the aisles in each section and how many shelves each
        aisle has. Copies are placed by picking from this layout, and a stocktake counts a section or one aisle against it.
        {!isAdmin && ' Only an administrator can change it.'}</p>
      <ErrorAlert error={error} />
      {isAdmin && <form className="card row" onSubmit={add} style={{ marginBottom: 16 }}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="New section, e.g. Maritime Collection" maxLength={100}
          style={{ flex: 1, minWidth: 200 }} aria-label="New section name" />
        <button className="btn primary" disabled={!name.trim()}><Icon name="plus" />Add section</button>
      </form>}
      <div className="card">
        <Table rows={data} rowKey={(s) => s.id} onRowClick={isAdmin ? setEditing : undefined} empty="No sections yet" columns={[
          { header: 'Section / area', render: (s) => <strong>{s.name}</strong> },
          { header: 'Aisles', render: (s) => s.aisles.length
            ? s.aisles.map((a) => `${a.name} (${shelvesLabel(a.shelves)})`).join(', ')
            : <span className="muted">None yet</span> },
          { header: 'Copies', render: (s) => s.copies },
          ...(isAdmin ? [{ header: 'Order', render: (s: LibrarySection) => {
            const i = data?.indexOf(s) ?? 0
            return <span className="row-actions" onClick={(e) => e.stopPropagation()}>
              <button className="btn small" disabled={i === 0} onClick={() => void move(i, -1)} aria-label={`Move ${s.name} up`}><Icon name="chevron-up" /></button>
              <button className="btn small" disabled={i === (data?.length ?? 0) - 1} onClick={() => void move(i, 1)} aria-label={`Move ${s.name} down`}><Icon name="chevron-down" /></button>
            </span>
          } }] : []),
        ]} />
      </div>
      {open && <SectionModal section={open} others={(data ?? []).filter((s) => s.id !== open.id)}
        onClose={() => setEditing(null)} onChanged={() => void reload()} onRemoved={() => { setEditing(null); void reload() }} />}
    </>
  )
}

function SectionModal({ section, others, onClose, onChanged, onRemoved }: {
  section: LibrarySection; others: LibrarySection[]; onClose: () => void; onChanged: () => void; onRemoved: () => void
}) {
  const [name, setName] = useState(section.name)
  const [count, setCount] = useState(1)
  const [shelves, setShelves] = useState(5)
  const [aisleName, setAisleName] = useState('')
  const [editingAisle, setEditingAisle] = useState<LibraryAisle | null>(null)
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  const mergeInto = others.find((o) => o.name.toLowerCase() === name.trim().toLowerCase())
  const renamed = name.trim() !== section.name

  async function run(fn: () => Promise<unknown>, ok: string, after = onChanged) {
    setError(null)
    try { await fn(); toast(ok); after() } catch (e) { setError(errorText(e)) }
  }

  async function saveName() {
    if (mergeInto && !confirm(`Move all ${section.copies} copies and the aisles of ${section.name} into ${mergeInto.name}, and remove ${section.name}?`)) return
    await run(() => api(`/api/sections/${section.id}`, { method: 'PUT', body: { name } }),
      mergeInto ? `Merged into ${mergeInto.name}` : 'Section renamed', mergeInto ? onRemoved : onChanged)
  }

  async function addAisles(e: FormEvent) {
    e.preventDefault()
    const body = aisleName.trim() ? { name: aisleName, shelves } : { count, shelves }
    await run(() => api(`/api/sections/${section.id}/aisles`, { body }),
      aisleName.trim() ? `Added aisle ${aisleName.trim()}` : `Added ${count} aisle${count === 1 ? '' : 's'}`)
    setAisleName('')
  }

  return (
    <Modal title={section.name} onClose={onClose} footer={<>
      <button className="btn danger" disabled={section.copies > 0}
        title={section.copies > 0 ? 'Move its copies first, or merge it into another section' : undefined}
        onClick={() => confirm(`Remove the section ${section.name} and its aisles?`)
          && void run(() => api(`/api/sections/${section.id}`, { method: 'DELETE' }), 'Section removed', onRemoved)}>Remove section</button>
      <span className="spacer" />
      <button className="btn" onClick={onClose}>Done</button>
    </>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <Field label="Section name" as="div" hint={`${section.copies} cop${section.copies === 1 ? 'y' : 'ies'}; renaming moves them too. Use another section's name to merge.`}>
          <div className="input-group">
            <input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} />
            <button className="btn" disabled={!renamed || !name.trim()} onClick={() => void saveName()}>{mergeInto ? 'Merge' : 'Rename'}</button>
          </div>
        </Field>

        <h3 style={{ margin: '8px 0 0' }}>Aisles</h3>
        <Table rows={section.aisles} rowKey={(a) => a.id} onRowClick={setEditingAisle} empty="No aisles yet" columns={[
          { header: 'Aisle', render: (a) => <strong>Aisle {a.name}</strong> },
          { header: 'Shelves', render: (a) => a.shelves === 1 ? 'Shelf 1 only' : `1 to ${a.shelves}` },
          { header: 'Copies', render: (a) => a.copies },
        ]} />
        <form className="card stack" onSubmit={addAisles} style={{ padding: 12 }}>
          <strong>Add aisles</strong>
          <div className="form-grid">
            <Field label="How many aisles" hint="Numbered after the library's highest aisle">
              <input type="number" min={1} max={30} value={count} disabled={!!aisleName.trim()}
                onChange={(e) => setCount(Math.max(1, Math.min(30, Number(e.target.value) || 1)))} /></Field>
            <Field label="Shelves in each aisle"><input type="number" min={1} max={50} value={shelves}
              onChange={(e) => setShelves(Math.max(1, Math.min(50, Number(e.target.value) || 1)))} /></Field>
            <Field label="Or one aisle named" hint="e.g. 3A; leave empty to number them"><input value={aisleName} maxLength={20}
              onChange={(e) => setAisleName(e.target.value)} /></Field>
          </div>
          <div className="row end"><button className="btn primary"><Icon name="plus" />Add</button></div>
        </form>
      </div>
      {editingAisle && <AisleModal aisle={editingAisle} section={section.name} onClose={() => setEditingAisle(null)}
        onSaved={() => { setEditingAisle(null); onChanged() }} />}
    </Modal>
  )
}

function AisleModal({ aisle, section, onClose, onSaved }: { aisle: LibraryAisle; section: string; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(aisle.name)
  const [shelves, setShelves] = useState(aisle.shelves)
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()

  async function save() {
    try {
      await api(`/api/aisles/${aisle.id}`, { method: 'PUT', body: { name, shelves } })
      toast('Aisle saved')
      onSaved()
    } catch (e) { setError(errorText(e)) }
  }

  async function remove() {
    if (!confirm(`Remove aisle ${aisle.name} from ${section}?`)) return
    try {
      await api(`/api/aisles/${aisle.id}`, { method: 'DELETE' })
      toast('Aisle removed')
      onSaved()
    } catch (e) { setError(errorText(e)) }
  }

  return (
    <Modal title={`${section} · Aisle ${aisle.name}`} onClose={onClose} footer={<>
      <button className="btn danger" onClick={remove} disabled={aisle.copies > 0}
        title={aisle.copies > 0 ? 'Move its copies first' : undefined}>Remove aisle</button>
      <span className="spacer" />
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn primary" onClick={save} disabled={!name.trim()}>Save</button>
    </>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <div className="form-grid">
          <Field label="Aisle name" hint="Renaming moves its copies too"><input value={name} maxLength={20} onChange={(e) => setName(e.target.value)} autoFocus /></Field>
          <Field label="Number of shelves" hint={`${aisle.copies} cop${aisle.copies === 1 ? 'y' : 'ies'} in this aisle`}>
            <input type="number" min={1} max={50} value={shelves} onChange={(e) => setShelves(Math.max(1, Math.min(50, Number(e.target.value) || 1)))} /></Field>
        </div>
      </div>
    </Modal>
  )
}

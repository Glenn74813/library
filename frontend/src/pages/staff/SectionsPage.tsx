import { useState, type FormEvent } from 'react'
import { api } from '../../api/client'
import type { LibrarySection } from '../../api/types'
import { hasRole, useSession } from '../../auth/AuthContext'
import { Icon } from '../../components/Icon'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Table } from '../../components/ui'
import { useAsync } from '../../hooks/useAsync'

/** The library's sections / areas. Copies and stocktakes pick from this list, so names are always spelled the same. */
export function SectionsPage() {
  const { data, error, reload } = useAsync(() => api<LibrarySection[]>('/api/sections'), [])
  const canEdit = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
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

  return (
    <>
      <div className="page-head"><h1>Sections</h1></div>
      <p className="muted" style={{ marginTop: -8 }}>The library's sections and areas. A copy's <strong>Section / area</strong> and a
        stocktake's location are picked from this list, so a stocktake can tell which copies are in the wrong section.</p>
      <ErrorAlert error={error} />
      {canEdit && <form className="card row" onSubmit={add} style={{ marginBottom: 16 }}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="New section, e.g. Maritime Collection" maxLength={100}
          style={{ flex: 1, minWidth: 200 }} aria-label="New section name" />
        <button className="btn primary" disabled={!name.trim()}><Icon name="plus" />Add section</button>
      </form>}
      <div className="card">
        <Table rows={data} rowKey={(s) => s.id} onRowClick={canEdit ? setEditing : undefined} empty="No sections yet" columns={[
          { header: 'Section / area', render: (s) => <strong>{s.name}</strong> },
          { header: 'Copies', render: (s) => s.copies },
          ...(canEdit ? [{ header: 'Order', render: (s: LibrarySection) => {
            const i = data?.indexOf(s) ?? 0
            return <span className="row-actions" onClick={(e) => e.stopPropagation()}>
              <button className="btn small" disabled={i === 0} onClick={() => void move(i, -1)} aria-label={`Move ${s.name} up`}><Icon name="chevron-up" /></button>
              <button className="btn small" disabled={i === (data?.length ?? 0) - 1} onClick={() => void move(i, 1)} aria-label={`Move ${s.name} down`}><Icon name="chevron-down" /></button>
            </span>
          } }] : []),
        ]} />
      </div>
      {editing && <SectionModal section={editing} others={(data ?? []).filter((s) => s.id !== editing.id)}
        onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void reload() }} />}
    </>
  )
}

function SectionModal({ section, others, onClose, onSaved }: {
  section: LibrarySection; others: LibrarySection[]; onClose: () => void; onSaved: () => void
}) {
  const [name, setName] = useState(section.name)
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  const mergeInto = others.find((o) => o.name.toLowerCase() === name.trim().toLowerCase())

  async function save() {
    if (mergeInto && !confirm(`Move all ${section.copies} copies from ${section.name} into ${mergeInto.name} and remove ${section.name}?`)) return
    try {
      await api(`/api/sections/${section.id}`, { method: 'PUT', body: { name } })
      toast(mergeInto ? `Merged into ${mergeInto.name}` : 'Section saved')
      onSaved()
    } catch (e) { setError(errorText(e)) }
  }

  async function remove() {
    if (!confirm(`Remove the section ${section.name}?`)) return
    try {
      await api(`/api/sections/${section.id}`, { method: 'DELETE' })
      toast('Section removed')
      onSaved()
    } catch (e) { setError(errorText(e)) }
  }

  return (
    <Modal title={`Section: ${section.name}`} onClose={onClose} footer={<>
      <button className="btn danger" onClick={remove} disabled={section.copies > 0}
        title={section.copies > 0 ? 'Move its copies first, or merge it into another section' : undefined}>Remove</button>
      <span className="spacer" />
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn primary" onClick={save} disabled={!name.trim()}>{mergeInto ? 'Merge' : 'Save'}</button>
    </>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <Field label="Name" hint={`${section.copies} cop${section.copies === 1 ? 'y' : 'ies'} in this section; renaming moves them too`}>
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} autoFocus /></Field>
        {mergeInto && <div className="alert warn small">{mergeInto.name} already exists. Saving merges {section.name} into it.</div>}
      </div>
    </Modal>
  )
}

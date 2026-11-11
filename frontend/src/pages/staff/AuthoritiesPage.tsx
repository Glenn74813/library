import { useState } from 'react'
import { api, qs } from '../../api/client'
import { hasRole, useSession } from '../../auth/AuthContext'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Table, Tabs } from '../../components/ui'
import { useAsync } from '../../hooks/useAsync'
import { useDebounced } from '../../hooks/useDebounced'

type AType = 'AUTHOR' | 'SUBJECT'
interface Row { id: number | null; heading: string; usage: number; variants: string[]; note: string | null }

/**
 * Authority control: every author/subject heading with its usage. Rename or merge a heading across
 * the whole catalog; the old form is kept as a variant so searches for it still work.
 */
export function AuthoritiesPage() {
  const canEdit = hasRole(useSession(), 'ADMIN', 'LIBRARIAN')
  const [type, setType] = useState<AType>('AUTHOR')
  const [q, setQ] = useState('')
  const dq = useDebounced(q)
  const { data, error, reload } = useAsync(() => api<Row[]>(`/api/authorities${qs({ type, q: dq })}`), [type, dq])
  const [editing, setEditing] = useState<Row | null>(null)

  return (
    <>
      <div className="page-head"><h1>Authorities</h1></div>
      <p className="muted" style={{ marginTop: -8 }}>Keep one form of each name and subject. <strong>Rename / merge</strong> fixes a heading in every
        record at once (e.g. “Sparks, Nick” → “Sparks, Nicholas”); the old form is kept as a variant, so OPAC searches for it still find the titles.</p>
      <Tabs value={type} onChange={(t) => { setType(t); setQ('') }} tabs={[{ id: 'AUTHOR', label: 'Authors' }, { id: 'SUBJECT', label: 'Subjects' }]} />
      <div className="card">
        <input type="search" placeholder="Filter headings…" value={q} onChange={(e) => setQ(e.target.value)} style={{ marginBottom: 12 }} />
        <ErrorAlert error={error} />
        <Table rows={data} rowKey={(r) => r.heading} empty="No headings" onRowClick={canEdit ? setEditing : undefined} columns={[
          { header: 'Heading', render: (r) => <strong>{r.heading}</strong> },
          { header: 'Titles', render: (r) => r.usage },
          { header: 'Variants (see from)', render: (r) => r.variants.length ? <span className="small">{r.variants.join(' · ')}</span> : <span className="muted">—</span> },
        ]} />
      </div>
      {editing && <HeadingModal type={type} row={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void reload() }} />}
    </>
  )
}

function HeadingModal({ type, row, onClose, onSaved }: { type: AType; row: Row; onClose: () => void; onSaved: () => void }) {
  const [to, setTo] = useState(row.heading)
  const [variants, setVariants] = useState(row.variants.join('\n'))
  const [note, setNote] = useState(row.note ?? '')
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()

  async function save() {
    setError(null)
    try {
      if (to.trim() !== row.heading) {
        const r = await api<{ recordsChanged: number }>('/api/authorities/rename', { body: { type, from: row.heading, to: to.trim() } })
        toast(`Renamed in ${r.recordsChanged} record(s)`)
      }
      const list = variants.split('\n').map((v) => v.trim()).filter(Boolean)
      const body = { type, heading: to.trim(), variants: list, note }
      // Re-read after a rename: the target heading may already have an authority entry.
      const existing = (await api<Row[]>(`/api/authorities${qs({ type, q: to.trim() })}`)).find((r) => r.heading.toLowerCase() === to.trim().toLowerCase())
      const merged = { ...body, variants: Array.from(new Set([...(existing?.variants ?? []), ...list])) }
      if (existing?.id) await api(`/api/authorities/${existing.id}`, { method: 'PUT', body: merged })
      else await api('/api/authorities', { body: merged })
      onSaved()
    } catch (e) { setError(errorText(e)) }
  }

  return (
    <Modal title={`${type === 'AUTHOR' ? 'Author' : 'Subject'}: ${row.heading}`} onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" onClick={save}>Save</button></>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <p className="muted small" style={{ margin: 0 }}>Used by {row.usage} title(s).</p>
        <Field label="Preferred heading" hint="Change it to rename the heading in every record; type an existing heading to merge into it">
          <input value={to} onChange={(e) => setTo(e.target.value)} /></Field>
        <Field label="Variant forms (one per line)" hint="Other spellings people search for; they lead to the preferred heading">
          <textarea value={variants} onChange={(e) => setVariants(e.target.value)} /></Field>
        <Field label="Note"><input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} /></Field>
      </div>
    </Modal>
  )
}

import { useState } from 'react'
import { api } from '../../api/client'
import type { CustomFieldDef } from '../../api/types'
import { Icon } from '../../components/Icon'
import { errorText, useToast } from '../../components/Toast'
import { ErrorAlert, Field, Modal, Table } from '../../components/ui'
import { useAsync } from '../../hooks/useAsync'

const TYPE_LABEL = { TEXT: 'Text', NUMBER: 'Number', DATE: 'Date', SELECT: 'Choice list' }

/** Library-defined extra catalog fields (accession number, donor, thesis adviser...). */
export function CatalogFieldsPage() {
  const { data, reload } = useAsync(() => api<CustomFieldDef[]>('/api/custom-fields'), [])
  const [editing, setEditing] = useState<Partial<CustomFieldDef> | null>(null)
  return (
    <>
      <div className="page-head">
        <h1>Catalog fields</h1>
        <button className="btn primary" onClick={() => setEditing({ type: 'TEXT', showInOpac: true, sortOrder: (data?.length ?? 0) + 1, options: [] })}>
          <Icon name="plus" />Add field</button>
      </div>
      <p className="muted" style={{ marginTop: -8 }}>Extra fields appear on every catalog record form, e.g. <em>Accession number</em>, <em>Donor</em>,
        <em> Thesis adviser</em>, <em>Program</em>. Fields marked “Show in OPAC” are visible to patrons.</p>
      <div className="card">
        <Table rows={data} rowKey={(f) => f.id} onRowClick={setEditing} empty="No custom fields yet" columns={[
          { header: 'Label', render: (f) => <strong>{f.label}</strong> },
          { header: 'Type', render: (f) => TYPE_LABEL[f.type] + (f.type === 'SELECT' ? ` (${f.options.length})` : '') },
          { header: 'In OPAC', render: (f) => f.showInOpac ? <span className="badge ok">Shown</span> : <span className="muted small">Staff only</span> },
          { header: 'Order', render: (f) => f.sortOrder },
        ]} />
      </div>
      {editing && <FieldModal value={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void reload() }} />}
    </>
  )
}

function FieldModal({ value, onClose, onSaved }: { value: Partial<CustomFieldDef>; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState(value)
  const [options, setOptions] = useState((value.options ?? []).join('\n'))
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  async function save() {
    const body = { label: f.label, type: f.type, options: options.split('\n').map((o) => o.trim()).filter(Boolean), showInOpac: f.showInOpac, sortOrder: f.sortOrder ?? 0 }
    try {
      if (f.id) await api(`/api/custom-fields/${f.id}`, { method: 'PUT', body })
      else await api('/api/custom-fields', { body })
      toast('Field saved')
      onSaved()
    } catch (e) { setError(errorText(e)) }
  }
  async function remove() {
    if (!confirm('Remove this field from the form? Values already entered are kept and come back if you re-create it.')) return
    try { await api(`/api/custom-fields/${f.id}`, { method: 'DELETE' }); onSaved() } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={f.id ? `Field: ${value.label}` : 'Add catalog field'} onClose={onClose} footer={<>
      {f.id && <button className="btn danger" onClick={remove}>Remove</button>}<span className="spacer" />
      <button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" onClick={save}>Save</button></>}>
      <div className="stack">
        <ErrorAlert error={error} />
        <div className="form-grid">
          <Field label="Label"><input value={f.label ?? ''} maxLength={80} onChange={(e) => setF({ ...f, label: e.target.value })} autoFocus /></Field>
          <Field label="Type">
            <select value={f.type} onChange={(e) => setF({ ...f, type: e.target.value as CustomFieldDef['type'] })}>
              {Object.entries(TYPE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </Field>
          <Field label="Order" hint="Position on the form"><input type="number" value={f.sortOrder ?? 0} onChange={(e) => setF({ ...f, sortOrder: Number(e.target.value) })} /></Field>
          <Field label="Visibility" as="div"><label className="check"><input type="checkbox" checked={!!f.showInOpac}
            onChange={(e) => setF({ ...f, showInOpac: e.target.checked })} /> Show in OPAC</label></Field>
          {f.type === 'SELECT' && <Field label="Choices (one per line)" className="span-all"><textarea value={options} onChange={(e) => setOptions(e.target.value)} /></Field>}
        </div>
      </div>
    </Modal>
  )
}

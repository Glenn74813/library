import { useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { DigitalInfo } from '../api/types'
import { fmtBytes, fmtDateTime } from '../format'
import { useAsync } from '../hooks/useAsync'
import { Icon } from './Icon'
import { errorText, useToast } from './Toast'
import { ErrorAlert, Field } from './ui'
import { useLibraryConfig } from '../api/library'

/**
 * Digital copy of a catalog record. A title is either school-owned (upload the PDF/EPUB; every
 * signed-in patron can read it) or commercial (vendor link only; the file is never uploaded).
 */
export function DigitalPanel({ recordId }: { recordId: number }) {
  const maxMb = useLibraryConfig().uploads.ebookMaxMb
  const { data: info, error, setData } = useAsync(() => api<DigitalInfo>(`/api/catalog/records/${recordId}/digital`), [recordId])
  const [mode, setMode] = useState<'owned' | 'commercial' | null>(null)
  const [busy, setBusy] = useState(false)
  const [url, setUrl] = useState('')
  const [provider, setProvider] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)
  const toast = useToast()

  async function upload(file: File) {
    if (file.size > maxMb * 1024 * 1024) return toast(`The file is too large (limit ${maxMb} MB)`, 'error')
    const fd = new FormData()
    fd.append('file', file)
    setBusy(true)
    try {
      setData(await api<DigitalInfo>(`/api/catalog/records/${recordId}/digital/file`, { body: fd }))
      setMode(null)
      toast('E-book uploaded — patrons can now read it in My Library')
    } catch (e) {
      toast(errorText(e), 'error')
    } finally {
      setBusy(false)
      if (fileRef.current) fileRef.current.value = ''
    }
  }

  async function removeFile() {
    if (!confirm('Remove the uploaded e-book file? Patrons will no longer be able to read it.')) return
    try {
      await api(`/api/catalog/records/${recordId}/digital/file`, { method: 'DELETE' })
      setData(await api<DigitalInfo>(`/api/catalog/records/${recordId}/digital`))
      toast('E-book file removed')
    } catch (e) {
      toast(errorText(e), 'error')
    }
  }

  async function saveLink(e?: FormEvent, clear = false) {
    e?.preventDefault()
    if (clear && !confirm('Remove the vendor link?')) return
    try {
      setData(await api<DigitalInfo>(`/api/catalog/records/${recordId}/digital/vendor`, {
        method: 'PUT', body: clear ? { url: null, provider: null } : { url, provider },
      }))
      setMode(null)
      toast(clear ? 'Vendor link removed' : 'Vendor link saved')
    } catch (err) {
      toast(errorText(err), 'error')
    }
  }

  const fileInput = (
    <input ref={fileRef} type="file" accept=".pdf,.epub,application/pdf,application/epub+zip" hidden
      onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} />
  )

  return (
    <div className="card stack">
      <div className="page-head" style={{ marginBottom: 0 }}>
        <h2 style={{ margin: 0 }}>E-book / digital copy</h2>
        {info?.kind === 'SCHOOL_OWNED' && <span className="badge ok">School-owned · readable by patrons</span>}
        {info?.kind === 'COMMERCIAL' && <span className="badge warn">Commercial · vendor platform</span>}
      </div>
      <ErrorAlert error={error} />
      {fileInput}

      {info?.kind === 'SCHOOL_OWNED' && (
        <>
          <dl className="form-grid" style={{ margin: 0 }}>
            <div><dt className="muted small">File</dt><dd style={{ margin: 0, overflowWrap: 'anywhere' }}>{info.filename}</dd></div>
            <div><dt className="muted small">Format · size</dt><dd style={{ margin: 0 }}>{info.format} · {fmtBytes(info.sizeBytes)}</dd></div>
            <div><dt className="muted small">Uploaded</dt><dd style={{ margin: 0 }}>{fmtDateTime(info.uploadedAt)} by {info.uploadedBy}</dd></div>
            <div><dt className="muted small">Times opened by patrons</dt><dd style={{ margin: 0 }}>{info.views}</dd></div>
          </dl>
          <div className="row actions-stretch">
            <Link className="btn" to={`/read/${recordId}`} target="_blank"><Icon name="eye" />Preview</Link>
            <button className="btn" disabled={busy} onClick={() => fileRef.current?.click()}><Icon name="upload" />{busy ? 'Uploading…' : 'Replace file'}</button>
            <button className="btn danger" onClick={removeFile}><Icon name="trash-2" />Remove</button>
          </div>
        </>
      )}

      {info?.kind === 'COMMERCIAL' && (
        <>
          <p style={{ margin: 0 }}>
            Patrons open this title on <strong>{info.externalProvider ?? 'the vendor platform'}</strong>:{' '}
            <a href={info.externalUrl!} target="_blank" rel="noopener noreferrer" style={{ overflowWrap: 'anywhere' }}>{info.externalUrl}</a>
          </p>
          <p className="muted small" style={{ margin: 0 }}>Commercial e-books are not uploaded to the library system.</p>
          <div className="row actions-stretch">
            <button className="btn" onClick={() => { setUrl(info.externalUrl ?? ''); setProvider(info.externalProvider ?? ''); setMode('commercial') }}>
              <Icon name="link" />Edit link</button>
            <button className="btn danger" onClick={() => saveLink(undefined, true)}><Icon name="trash-2" />Remove link</button>
          </div>
        </>
      )}

      {info && !info.kind && mode === null && (
        <>
          <p className="muted" style={{ margin: 0 }}>No digital copy. Choose how patrons get this title as an e-book:</p>
          <div className="grid cols-2">
            <button className="card choice" onClick={() => fileRef.current?.click()} disabled={busy}>
              <Icon name="upload" className="choice-icon" />
              <strong>{busy ? 'Uploading…' : 'Upload a school-owned e-book'}</strong>
              <span className="muted small">PDF or EPUB, up to {maxMb} MB. Every signed-in patron can read it in the app.</span>
            </button>
            <button className="card choice" onClick={() => { setUrl(''); setProvider(''); setMode('commercial') }}>
              <Icon name="link" className="choice-icon" />
              <strong>Link a commercial e-book</strong>
              <span className="muted small">The file stays with the vendor; patrons open it on the vendor's platform.</span>
            </button>
          </div>
        </>
      )}

      {mode === 'commercial' && (
        <form className="stack" onSubmit={saveLink}>
          <div className="form-grid">
            <Field label="Vendor link *" hint="Address of this title on the vendor's platform" className="span-all">
              <input type="url" required placeholder="https://" value={url} onChange={(e) => setUrl(e.target.value)} />
            </Field>
            <Field label="Platform / provider" hint="e.g. the e-book service the library subscribes to">
              <input value={provider} onChange={(e) => setProvider(e.target.value)} />
            </Field>
          </div>
          <div className="row end actions-stretch">
            <button type="button" className="btn" onClick={() => setMode(null)}>Cancel</button>
            <button className="btn primary">Save link</button>
          </div>
        </form>
      )}
    </div>
  )
}

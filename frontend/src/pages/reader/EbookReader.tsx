import { useCallback, useEffect, useRef, useState } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router-dom'
import * as pdfjs from 'pdfjs-dist'
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import ePub, { type Rendition } from 'epubjs'
import { api, fetchBytes } from '../../api/client'
import type { OpacDetail } from '../../api/types'
import { hasRole, STAFF_ROLES, useSession } from '../../auth/AuthContext'
import { Icon } from '../../components/Icon'
import { errorText } from '../../components/Toast'

pdfjs.GlobalWorkerOptions.workerSrc = pdfWorkerUrl

interface Loaded { data: ArrayBuffer; format: 'PDF' | 'EPUB' }

/**
 * In-app reader for school-owned e-books (PDF and EPUB), usable on phones, tablets and PCs.
 * The file is fetched with the reader's own session and kept in memory only; there is no
 * download button. Patrons read through /api/me, staff preview through the catalog API.
 */
export function EbookReader() {
  const { recordId } = useParams()
  const session = useSession()
  const navigate = useNavigate()
  const isPatron = hasRole(session, 'PATRON')
  const isStaff = hasRole(session, ...STAFF_ROLES)
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [title, setTitle] = useState('E-book')

  useEffect(() => {
    if (!isPatron && !isStaff) return
    let cancelled = false
    const path = isPatron ? `/api/me/ebooks/${recordId}/content` : `/api/catalog/records/${recordId}/digital/file`
    fetchBytes(path)
      .then(({ data, contentType }) => {
        if (!cancelled) setLoaded({ data, format: contentType.includes('epub') ? 'EPUB' : 'PDF' })
      })
      .catch((e) => !cancelled && setError(errorText(e)))
    api<OpacDetail>(`/api/opac/records/${recordId}`, { anonymous: true })
      .then((d) => !cancelled && setTitle(d.record.title))
      .catch(() => {})
    return () => { cancelled = true }
  }, [recordId, isPatron, isStaff])

  if (!isPatron && !isStaff) return <Navigate to={`/my/login?next=/read/${recordId}`} replace />

  const back = () => (window.history.length > 1 ? navigate(-1) : navigate(isPatron ? '/my' : `/staff/catalog/${recordId}`))
  const bar = (controls?: React.ReactNode) => (
    <div className="reader-bar">
      <button className="btn" onClick={back} aria-label="Back"><Icon name="arrow-left" /></button>
      <span className="title" title={title}>{title}</span>
      {controls}
    </div>
  )

  return (
    <div className="reader" onContextMenu={(e) => e.preventDefault()}>
      {error ? <>{bar()}<div className="reader-notice"><Icon name="lock" size={32} /><p>{error}</p></div></>
        : !loaded ? <>{bar()}<div className="reader-notice">Opening e-book…</div></>
          : loaded.format === 'PDF' ? <PdfView data={loaded.data} bar={bar} />
            : <EpubView data={loaded.data} bar={bar} />}
    </div>
  )
}

type Bar = (controls?: React.ReactNode) => React.ReactNode

function PdfView({ data, bar }: { data: ArrayBuffer; bar: Bar }) {
  const [doc, setDoc] = useState<pdfjs.PDFDocumentProxy | null>(null)
  const [page, setPage] = useState(1)
  const [zoom, setZoom] = useState(1)
  const [error, setError] = useState<string | null>(null)
  const [width, setWidth] = useState(0)
  const bodyRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const task = pdfjs.getDocument({ data: new Uint8Array(data.slice(0)) })
    task.promise.then(setDoc).catch((e) => setError(errorText(e)))
    return () => { void task.destroy() }
  }, [data])

  useEffect(() => {
    const el = bodyRef.current
    if (!el) return
    const ro = new ResizeObserver(() => setWidth(el.clientWidth))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    if (!doc || !width || !canvasRef.current) return
    let cancelled = false
    let render: pdfjs.RenderTask | null = null
    void doc.getPage(page).then((p) => {
      if (cancelled || !canvasRef.current) return
      const canvas = canvasRef.current
      const fit = Math.min(width - 24, 900) / p.getViewport({ scale: 1 }).width
      const dpr = window.devicePixelRatio || 1
      const viewport = p.getViewport({ scale: fit * zoom * dpr })
      canvas.width = Math.floor(viewport.width)
      canvas.height = Math.floor(viewport.height)
      canvas.style.width = `${Math.floor(viewport.width / dpr)}px`
      canvas.style.height = `${Math.floor(viewport.height / dpr)}px`
      render = p.render({ canvas, viewport })
      render.promise.catch(() => {})
    })
    return () => { cancelled = true; render?.cancel() }
  }, [doc, page, zoom, width])

  const pages = doc?.numPages ?? 0
  const go = useCallback((d: number) => setPage((p) => Math.min(Math.max(1, p + d), pages || 1)), [pages])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowRight' || e.key === 'PageDown') go(1)
      if (e.key === 'ArrowLeft' || e.key === 'PageUp') go(-1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [go])

  useEffect(() => { bodyRef.current?.scrollTo({ top: 0 }) }, [page])

  return (
    <>
      {bar(<>
        <button className="btn" onClick={() => setZoom((z) => Math.max(0.5, +(z - 0.25).toFixed(2)))} aria-label="Zoom out"><Icon name="zoom-out" /></button>
        <button className="btn" onClick={() => setZoom((z) => Math.min(3, +(z + 0.25).toFixed(2)))} aria-label="Zoom in"><Icon name="zoom-in" /></button>
        <button className="btn" onClick={() => go(-1)} disabled={page <= 1} aria-label="Previous page"><Icon name="chevron-left" /></button>
        <span className="pageno">{pages ? `${page} / ${pages}` : '…'}</span>
        <button className="btn" onClick={() => go(1)} disabled={page >= pages} aria-label="Next page"><Icon name="chevron-right" /></button>
      </>)}
      <div className="reader-body" ref={bodyRef}>
        {error ? <div className="reader-notice">{error}</div> : <canvas ref={canvasRef} />}
      </div>
    </>
  )
}

function EpubView({ data, bar }: { data: ArrayBuffer; bar: Bar }) {
  const ref = useRef<HTMLDivElement>(null)
  const rendition = useRef<Rendition | null>(null)
  const [atStart, setAtStart] = useState(true)
  const [atEnd, setAtEnd] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!ref.current) return
    const book = ePub(data.slice(0))
    book.opened.catch((e: unknown) => { console.error('EPUB open failed', e); setError(errorText(e)) })
    const r = book.renderTo(ref.current, { width: '100%', height: '100%', spread: 'auto', flow: 'paginated' })
    rendition.current = r
    r.on('relocated', (loc: { atStart?: boolean; atEnd?: boolean }) => {
      setAtStart(!!loc.atStart)
      setAtEnd(!!loc.atEnd)
    })
    r.display().catch((e: unknown) => { console.error('EPUB display failed', e); setError(errorText(e)) })
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowRight') void r.next()
      if (e.key === 'ArrowLeft') void r.prev()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      book.destroy()
    }
  }, [data])

  return (
    <>
      {bar(<>
        <button className="btn" disabled={atStart} onClick={() => void rendition.current?.prev()} aria-label="Previous page"><Icon name="chevron-left" /></button>
        <button className="btn" disabled={atEnd} onClick={() => void rendition.current?.next()} aria-label="Next page"><Icon name="chevron-right" /></button>
      </>)}
      {error && <div className="reader-notice">Could not open this EPUB: {error}</div>}
      <div className="reader-epub" ref={ref} style={error ? { display: 'none' } : undefined} />
    </>
  )
}

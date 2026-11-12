import { useEffect, useState, type InputHTMLAttributes, type ReactNode } from 'react'
import type { DigitalSummary, Page } from '../api/types'
import { label } from '../format'
import { Icon } from './Icon'
import { download } from '../api/client'

// ------------------------------------------------------------------ table

export interface Column<T> {
  header: string
  render: (row: T) => ReactNode
  className?: string
}

/** Data table that collapses into labelled cards on small screens (see styles.css). */
export function Table<T>({ columns, rows, rowKey, onRowClick, empty = 'Nothing to show' }: {
  columns: Column<T>[]
  rows: T[] | undefined
  rowKey: (row: T) => string | number
  onRowClick?: (row: T) => void
  empty?: string
}) {
  return (
    <div className="table-wrap">
      <table className="data">
        <thead>
          <tr>{columns.map((c) => <th key={c.header} className={c.className}>{c.header}</th>)}</tr>
        </thead>
        <tbody>
          {!rows || rows.length === 0 ? (
            <tr><td className="empty" colSpan={columns.length}>{rows ? empty : 'Loading…'}</td></tr>
          ) : (
            rows.map((row) => (
              <tr key={rowKey(row)} className={onRowClick ? 'clickable' : undefined}
                onClick={onRowClick ? () => onRowClick(row) : undefined}>
                {columns.map((c) => (
                  <td key={c.header} data-label={c.header} className={c.className}><div className="cell">{c.render(row)}</div></td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}

export function Pager({ page, onPage }: { page: Page<unknown> | undefined; onPage: (p: number) => void }) {
  if (!page || page.totalPages <= 1) {
    return page ? <div className="pager muted small">{page.totalElements} result(s)</div> : null
  }
  return (
    <div className="pager">
      <span className="muted small">
        Page {page.page + 1} of {page.totalPages} · {page.totalElements} result(s)
      </span>
      <div className="row">
        <button className="btn small" disabled={page.page === 0} onClick={() => onPage(page.page - 1)}><Icon name="chevron-left" />Prev</button>
        <button className="btn small" disabled={page.page + 1 >= page.totalPages} onClick={() => onPage(page.page + 1)}>Next<Icon name="chevron-right" /></button>
      </div>
    </div>
  )
}

// ------------------------------------------------------------------ modal

/**
 * Stops the page behind an open dialog from scrolling. The lock goes on <html>, which scrolls the
 * page: locking <body> instead turns it into its own scroll box, and the sticky sidebar and top
 * bar then slide away with the page. Counted, so a dialog opened from another keeps the lock.
 */
let scrollLocks = 0
function lockPageScroll() {
  if (scrollLocks++ === 0) document.documentElement.classList.add('scroll-locked')
  return () => {
    if (--scrollLocks === 0) document.documentElement.classList.remove('scroll-locked')
  }
}

export function Modal({ title, onClose, children, footer }: {
  title: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  useEffect(lockPageScroll, [])
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <header>
          <h2>{title}</h2>
          <button className="icon-btn no-print" aria-label="Close" onClick={onClose}><Icon name="x" /></button>
        </header>
        <div className="modal-body">{children}</div>
        {footer && <footer className="no-print">{footer}</footer>}
      </div>
    </div>
  )
}

// ------------------------------------------------------------------ small pieces

export function Field({ label: text, hint, children, className, as: Tag = 'label' }: {
  label: string
  hint?: ReactNode
  children: ReactNode
  className?: string
  /** Use 'div' when the control brings its own <label> (checkboxes), since labels can't nest. */
  as?: 'label' | 'div'
}) {
  return (
    <Tag className={`field ${className ?? ''}`}>
      <span>{text}</span>
      {children}
      {hint ? <span className="hint">{hint}</span> : <span className="hint" aria-hidden />}
    </Tag>
  )
}

export function ErrorAlert({ error }: { error: string | null | undefined }) {
  return error ? <div className="alert error" role="alert">{error}</div> : null
}

export function Stat({ label: text, value, tone }: { label: string; value: ReactNode; tone?: 'warn' | 'bad' }) {
  return (
    <div className={`stat ${tone ?? ''}`}>
      <div className="label">{text}</div>
      <div className="value">{value}</div>
    </div>
  )
}

const STATUS_TONE: Record<string, string> = {
  AVAILABLE: 'ok', ON_LOAN: 'info', ON_HOLD: 'warn', LOST: 'bad', MISSING: 'bad', DAMAGED: 'warn',
  WITHDRAWN: '', SCHEDULED: 'info', PENDING: 'info', READY: 'ok', FULFILLED: 'ok', CANCELLED: '', EXPIRED: 'warn',
  UNPAID: 'bad', PAID: 'ok', WAIVED: '', ALARM: 'bad', TAMPER: 'bad', POWER_LOSS: 'warn',
  POWER_RESTORED: 'ok', VISITOR: 'info', EMERGENCY_ON: 'bad', EMERGENCY_OFF: 'ok', BROADCAST: 'warn',
  IN: 'ok', OUT: 'info', OK: 'ok', CHECK: 'warn', MISPLACED: 'warn', UNKNOWN: 'bad', DUPLICATE: '',
  APPROVED: 'ok', REJECTED: 'bad',
}

const STATUS_TEXT: Record<string, string> = {
  SCHEDULED: 'Booked', PENDING: 'Waiting', READY: 'Ready for pickup', FULFILLED: 'Released', EXPIRED: 'Not picked up',
}

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge ${STATUS_TONE[status] ?? ''}`}>{STATUS_TEXT[status] ?? label(status)}</span>
}

export function Tabs<T extends string>({ tabs, value, onChange }: {
  tabs: { id: T; label: string }[]
  value: T
  onChange: (v: T) => void
}) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <button key={t.id} role="tab" aria-selected={value === t.id} className={value === t.id ? 'active' : ''}
          onClick={() => onChange(t.id)}>{t.label}</button>
      ))}
    </div>
  )
}

// ------------------------------------------------------------------ charts (dependency-free)

export function BarList({ data, empty = 'No data' }: { data: { label: string; count: number }[]; empty?: string }) {
  if (!data.length) return <p className="muted small">{empty}</p>
  const max = Math.max(...data.map((d) => d.count), 1)
  return (
    <div className="bars">
      {data.map((d) => (
        <div className="bar-row" key={d.label}>
          <span className="bar-label" title={d.label}>{d.label}</span>
          <div className="bar-track"><div className="bar-fill" style={{ width: `${(d.count / max) * 100}%` }} /></div>
          <span className="bar-value">{d.count}</span>
        </div>
      ))}
    </div>
  )
}

/** Small badge telling whether a title can be read in the app (school-owned) or on a vendor platform. */
export function EbookBadge({ ebook }: { ebook: DigitalSummary }) {
  return ebook.kind === 'SCHOOL_OWNED'
    ? <span className="badge ok ebook-badge"><Icon name="tablet" size={12} />E-book · {ebook.format}</span>
    : <span className="badge warn ebook-badge"><Icon name="link" size={12} />E-book · {ebook.provider ?? 'vendor'}</span>
}

/** Adds format=csv|pdf to an export URL that may already carry a query string. */
export const withFormat = (path: string, format: 'csv' | 'pdf') => `${path}${path.includes('?') ? '&' : '?'}format=${format}`

/** "Export…" picker that downloads the same report as Excel (CSV) or a printable PDF. */
export function ExportMenu({ path, name, label = 'Export…' }: { path: string; name: string; label?: string }) {
  return (
    <select className="btn" style={{ width: 'auto' }} value="" aria-label={label} onChange={(e) => {
      const f = e.target.value as 'csv' | 'pdf'
      void download(withFormat(path, f), `${name}.${f}`)
    }}>
      <option value="" disabled>{label}</option>
      <option value="csv">Excel (CSV)</option>
      <option value="pdf">PDF</option>
    </select>
  )
}

/** Password or PIN box with an eye button that shows or hides what was typed. */
export function PasswordInput(props: Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>) {
  const [shown, setShown] = useState(false)
  return (
    <span className="password-input">
      <input {...props} type={shown ? 'text' : 'password'} />
      <button type="button" className="password-eye" onClick={() => setShown((v) => !v)} tabIndex={-1}
        aria-label={shown ? 'Hide password' : 'Show password'} aria-pressed={shown} title={shown ? 'Hide' : 'Show'}>
        <Icon name={shown ? 'eye-off' : 'eye'} />
      </button>
    </span>
  )
}

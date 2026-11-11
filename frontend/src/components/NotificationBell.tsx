import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { Task } from '../api/types'
import { Icon, type IconName } from './Icon'
import { useToast } from './Toast'

const POLL_MS = 60_000

const SEVERITY: Record<Task['severity'], { tone: string; icon: IconName; label: string }> = {
  DANGER: { tone: 'bad', icon: 'alert-octagon', label: 'Needs action now' },
  WARNING: { tone: 'warn', icon: 'alert-triangle', label: 'Needs attention' },
  INFO: { tone: 'info', icon: 'bell', label: 'For your information' },
}

/** Staff notification bell listing actionable tasks; pops a toast when a new urgent one appears. */
export function NotificationBell() {
  const [tasks, setTasks] = useState<Task[] | null>(null)
  const [open, setOpen] = useState(false)
  const seen = useRef<Set<string> | null>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  const location = useLocation()
  const navigate = useNavigate()
  const toast = useToast()

  const load = useCallback(async () => {
    try {
      const list = await api<Task[]>('/api/tasks')
      // Announce urgent tasks that weren't there on the previous check (not on first load).
      if (seen.current) {
        list.filter((t) => t.severity === 'DANGER' && !seen.current!.has(t.id)).forEach((t) => toast(t.title, 'error'))
      }
      seen.current = new Set(list.map((t) => t.id))
      setTasks(list)
    } catch {
      /* keep the last list; the bell is not critical */
    }
  }, [toast])

  useEffect(() => {
    void load()
    const t = setInterval(load, POLL_MS)
    return () => clearInterval(t)
  }, [load])

  useEffect(() => {
    setOpen(false)
    void load()
  }, [location.pathname]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => { if (!boxRef.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey) }
  }, [open])

  const urgent = tasks?.filter((t) => t.severity !== 'INFO').length ?? 0
  const total = tasks?.length ?? 0
  const hasDanger = tasks?.some((t) => t.severity === 'DANGER') ?? false

  return (
    <div className="bell" ref={boxRef}>
      <button className="icon-btn" onClick={() => setOpen((o) => !o)} aria-expanded={open}
        aria-label={`Notifications: ${total} task(s)${urgent ? `, ${urgent} need attention` : ''}`}>
        <Icon name="bell" />
        {total > 0 && <span className={`bell-badge ${hasDanger ? 'bad' : urgent ? 'warn' : ''}`}>{total > 99 ? '99+' : total}</span>}
      </button>
      {open && (
        <div className="bell-panel" role="dialog" aria-label="Tasks">
          <div className="bell-head">
            <strong>Tasks</strong>
            <span className="muted small">{total ? `${total} open` : ''}</span>
          </div>
          {!tasks ? <p className="muted small bell-empty">Loading…</p>
            : tasks.length === 0 ? (
              <div className="bell-empty"><Icon name="check-circle" size={28} /><p>All clear — nothing needs action.</p></div>
            ) : (
              <ul className="bell-list">
                {tasks.map((t) => (
                  <li key={t.id}>
                    <button className={`bell-item ${SEVERITY[t.severity].tone}`} onClick={() => navigate(t.link)}>
                      <Icon name={SEVERITY[t.severity].icon} className="bell-item-icon" />
                      <span>
                        <strong>{t.title}</strong>
                        <span className="muted small">{t.detail}</span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
        </div>
      )}
    </div>
  )
}

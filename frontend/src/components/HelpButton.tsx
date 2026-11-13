import { Fragment, useEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useLocation } from 'react-router-dom'
import { helpFor, type Help } from '../help'
import { Icon } from './Icon'
import { lockPageScroll } from './ui'

/** The (i) next to a module's title: opens "How to use" for the module, from help.ts, in a side panel. */
export function HelpButton() {
  const help = helpFor(useLocation().pathname)
  const [open, setOpen] = useState(false)
  if (!help) return null
  return (
    <>
      <button type="button" className="help-btn" onClick={() => setOpen(true)} aria-label={`How to use ${help.title}`}
        title={`How to use ${help.title}`}><Icon name="info" /></button>
      {open && <HelpPanel help={help} onClose={() => setOpen(false)} />}
    </>
  )
}

function HelpPanel({ help, onClose }: { help: Help; onClose: () => void }) {
  const close = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    close.current?.focus()
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  useEffect(lockPageScroll, [])
  // On <body>, above the sidebar and top bar, whatever the page's own layers are.
  return createPortal(
    <div className="help-backdrop" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <aside className="help-panel" role="dialog" aria-modal="true" aria-labelledby="help-title">
        <div className="help-top">
          <span className="help-eyebrow">How to use</span>
          <button ref={close} type="button" className="icon-btn" onClick={onClose} aria-label="Close"><Icon name="x" /></button>
        </div>
        <h2 id="help-title" className="help-title">{help.title}</h2>
        <p className="help-about">{rich(help.about)}</p>
        <p className="help-who"><strong>Who uses it:</strong> {rich(help.who)}</p>
        <hr />
        {help.sections.map((sec) => (
          <section key={sec.heading}>
            <h3>{sec.heading}</h3>
            {sec.steps.length > 1 ? <ol>{sec.steps.map((st) => <li key={st}>{rich(st)}</li>)}</ol> : <p>{rich(sec.steps[0])}</p>}
          </section>
        ))}
        {help.connects && help.connects.length > 0 && (
          <section>
            <h3>How it connects to other modules</h3>
            <ul>{help.connects.map(([m, t]) => <li key={m}><strong>{m}:</strong> {rich(t)}</li>)}</ul>
          </section>
        )}
      </aside>
    </div>,
    document.body,
  )
}

/** Text with **screen labels** shown in bold. */
function rich(text: string): ReactNode {
  return text.split('**').map((part, i) => i % 2 ? <strong key={i}>{part}</strong> : <Fragment key={i}>{part}</Fragment>)
}

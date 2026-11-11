import { useEffect, useRef, useState } from 'react'
import { api, qs } from '../api/client'

/**
 * Semicolon-separated heading field (authors / subjects) that suggests headings already used in
 * the catalog or on file in the authority list, for the part currently being typed.
 */
export function HeadingInput({ type, value, onChange }: {
  type: 'AUTHOR' | 'SUBJECT'; value: string; onChange: (v: string) => void
}) {
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [open, setOpen] = useState(false)
  const timer = useRef<number>(0)
  const parts = value.split(';')
  const current = parts[parts.length - 1].trim()

  useEffect(() => {
    window.clearTimeout(timer.current)
    if (current.length < 2) { setSuggestions([]); return }
    timer.current = window.setTimeout(() => {
      api<string[]>(`/api/authorities/suggest${qs({ type, q: current })}`)
        .then((s) => setSuggestions(s.filter((h) => h.toLowerCase() !== current.toLowerCase())))
        .catch(() => setSuggestions([]))
    }, 250)
    return () => window.clearTimeout(timer.current)
  }, [current, type])

  function pick(h: string) {
    const head = parts.slice(0, -1).map((p) => p.trim()).filter(Boolean)
    onChange([...head, h].join('; ') + '; ')
    setSuggestions([])
  }

  return (
    <div className="suggest">
      <input value={value} onChange={(e) => { onChange(e.target.value); setOpen(true) }} onFocus={() => setOpen(true)}
        onBlur={() => window.setTimeout(() => setOpen(false), 150)} autoComplete="off" />
      {open && suggestions.length > 0 && (
        <ul className="suggest-list" role="listbox">
          {suggestions.map((s) => (
            <li key={s}><button type="button" onMouseDown={(e) => e.preventDefault()} onClick={() => pick(s)}>{s}</button></li>
          ))}
        </ul>
      )}
    </div>
  )
}

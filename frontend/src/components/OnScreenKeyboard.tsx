import { Icon } from './Icon'

const ROWS = [
  ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'],
  ['Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'],
  ['A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L', '-'],
  ['Z', 'X', 'C', 'V', 'B', 'N', 'M', ',', '.', '⌫'],
]

/** Large touch keyboard for kiosks (no OS keyboard is available in a locked kiosk browser). */
export function OnScreenKeyboard({ onKey, onEnter, enterLabel = 'Search' }: {
  onKey: (key: string) => void
  onEnter?: () => void
  enterLabel?: string
}) {
  return (
    <div className="osk" aria-label="On-screen keyboard">
      {ROWS.map((row, i) => (
        <div className="osk-row" key={i}>
          {row.map((k) => (
            <button key={k} type="button" className="osk-key" onClick={() => onKey(k === '⌫' ? 'BACKSPACE' : k)}
              aria-label={k === '⌫' ? 'Backspace' : k}>{k === '⌫' ? <Icon name="delete" /> : k}</button>
          ))}
        </div>
      ))}
      <div className="osk-row">
        <button type="button" className="osk-key wide" onClick={() => onKey('CLEAR')}>Clear</button>
        <button type="button" className="osk-key space" onClick={() => onKey(' ')}>Space</button>
        {onEnter && <button type="button" className="osk-key wide primary" onClick={onEnter}>{enterLabel}</button>}
      </div>
    </div>
  )
}

export function applyKey(value: string, key: string) {
  if (key === 'BACKSPACE') return value.slice(0, -1)
  if (key === 'CLEAR') return ''
  return value + (key.length === 1 ? key.toLowerCase() : key)
}

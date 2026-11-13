import { useState } from 'react'
import { DEFAULT_STATION_URL, getStation, setStation, station } from '../api/rfidStation'
import { Icon } from './Icon'

/**
 * "RFID station on this computer": turns on the RFID bridge for this desk, so checkouts switch the tag's
 * security off, returns switch it on, and copies can be tagged from the copy form. Saved on this computer.
 */
export function RfidStationCard() {
  const [c, setC] = useState(getStation)
  const [result, setResult] = useState<string | null>(null)
  const save = (next: typeof c) => { setC(next); setStation(next); setResult(null) }

  async function test() {
    setResult('Checking…')
    try {
      const s = await station.status(c.url)
      setResult(`Connected: ${s.reader} reader, ${s.writer === 'none' ? 'reading only' : `${s.writer} writer`}, ${s.tags} tag(s) in range now.`)
    } catch (e) {
      setResult((e as Error).message)
    }
  }

  return (
    <details className="card">
      <summary style={{ cursor: 'pointer' }}><strong><Icon name="tablet" /> RFID station on this computer</strong>
        {c.enabled && <span className="badge ok" style={{ marginLeft: 8 }}>On</span>}</summary>
      <div className="stack" style={{ gap: 8, marginTop: 8 }}>
        <label className="check"><input type="checkbox" checked={c.enabled} onChange={(e) => save({ ...c, enabled: e.target.checked })} />
          Use the RFID bridge on this computer (security off at checkout, on at return; write tags)</label>
        <div className="input-group">
          <input className="mono" value={c.url} onChange={(e) => save({ ...c, url: e.target.value })} placeholder={DEFAULT_STATION_URL}
            aria-label="RFID bridge address" />
          <button className="btn" type="button" onClick={() => void test()}>Test</button>
        </div>
        {result && <div className="small">{result}</div>}
        <p className="muted small" style={{ margin: 0 }}>The bridge (<span className="mono">rfid-bridge</span>) runs next to the reader and works
          with serial, network, command-line or push readers. Keyboard-wedge readers need nothing: they type into the scan box.</p>
      </div>
    </details>
  )
}

import { useEffect, useRef, useState } from 'react'
import type { KioskConfig } from '../api/types'
import { Icon } from './Icon'

const SHOW_MS = 60_000

/** Shows (and speaks) an emergency/announcement broadcast pushed from the gate console. */
export function BroadcastOverlay({ broadcast }: { broadcast: KioskConfig['broadcast'] | undefined }) {
  const [visible, setVisible] = useState<string | null>(null)
  const lastSeen = useRef<string | null>(sessionStorageGet())

  useEffect(() => {
    if (!broadcast) return
    const age = Date.now() - new Date(broadcast.at).getTime()
    if (broadcast.at === lastSeen.current || age > 10 * 60_000) return
    lastSeen.current = broadcast.at
    try { sessionStorage.setItem('smartlib.lastBroadcast', broadcast.at) } catch { /* ignore */ }
    setVisible(broadcast.message)
    if ('speechSynthesis' in window) {
      const say = () => {
        const u = new SpeechSynthesisUtterance(broadcast.message)
        u.rate = 0.95
        window.speechSynthesis.speak(u)
      }
      say()
      setTimeout(say, 8000)
    }
    const t = setTimeout(() => setVisible(null), SHOW_MS)
    return () => clearTimeout(t)
  }, [broadcast])

  if (!visible) return null
  return (
    <div className="broadcast-overlay" role="alert" onClick={() => setVisible(null)}>
      <div><Icon name="volume-2" size="1.6em" /><br />{visible}<div style={{ fontSize: '0.4em', marginTop: '2em', fontWeight: 400 }}>Tap to dismiss</div></div>
    </div>
  )
}

function sessionStorageGet() {
  try { return sessionStorage.getItem('smartlib.lastBroadcast') } catch { return null }
}

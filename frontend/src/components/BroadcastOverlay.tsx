import { useEffect, useRef, useState } from 'react'
import type { KioskConfig, VoiceSettings } from '../api/types'
import { speak } from '../speech'
import { Icon } from './Icon'

const SHOW_MS = 60_000
const DEFAULT_VOICE: VoiceSettings = { name: '', lang: 'en-US', rate: 0.95, pitch: 1, repeat: 2 }

/** Shows (and speaks) an emergency/announcement broadcast pushed from the gate console. */
export function BroadcastOverlay({ broadcast, voice }: { broadcast: KioskConfig['broadcast'] | undefined; voice?: VoiceSettings }) {
  const [visible, setVisible] = useState<string | null>(null)
  const lastSeen = useRef<string | null>(sessionStorageGet())

  useEffect(() => {
    if (!broadcast) return
    const age = Date.now() - new Date(broadcast.at).getTime()
    if (broadcast.at === lastSeen.current || age > 10 * 60_000) return
    lastSeen.current = broadcast.at
    try { sessionStorage.setItem('smartlib.lastBroadcast', broadcast.at) } catch { /* ignore */ }
    setVisible(broadcast.message)
    void speak(broadcast.message, voice ?? DEFAULT_VOICE)
    const t = setTimeout(() => setVisible(null), SHOW_MS)
    return () => clearTimeout(t)
  }, [broadcast]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!visible) return null
  return (
    <div className="broadcast-overlay" role="alert" onClick={() => { setVisible(null); window.speechSynthesis?.cancel() }}>
      <div><Icon name="volume-2" size="1.6em" /><br />{visible}<div style={{ fontSize: '0.4em', marginTop: '2em', fontWeight: 400 }}>Tap to dismiss</div></div>
    </div>
  )
}

function sessionStorageGet() {
  try { return sessionStorage.getItem('smartlib.lastBroadcast') } catch { return null }
}

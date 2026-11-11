import { useEffect, useRef, useState } from 'react'
import type { KioskConfig, VoiceSettings } from '../api/types'
import { fetchBytes } from '../api/client'
import { speak } from '../speech'
import { Icon } from './Icon'

const SHOW_MS = 60_000
const DEFAULT_VOICE: VoiceSettings = { name: '', lang: 'en-US', rate: 0.95, pitch: 1, repeat: 2 }

/** Shows (and speaks) an emergency/announcement broadcast pushed from the gate console. */
export function BroadcastOverlay({ broadcast, voice, audioPath = '/api/kiosk/broadcast-audio', anonymous = false }: {
  broadcast: KioskConfig['broadcast'] | undefined
  voice?: VoiceSettings
  /** Where to fetch the recording: kiosks use their session, public OPAC screens the open endpoint. */
  audioPath?: string
  anonymous?: boolean
}) {
  const [visible, setVisible] = useState<string | null>(null)
  const lastSeen = useRef<string | null>(sessionStorageGet())
  const audio = useRef<HTMLAudioElement | null>(null)
  const stopped = useRef(false)

  useEffect(() => {
    if (!broadcast) return
    const age = Date.now() - new Date(broadcast.at).getTime()
    if (broadcast.at === lastSeen.current || age > 10 * 60_000) return
    lastSeen.current = broadcast.at
    try { sessionStorage.setItem('smartlib.lastBroadcast', broadcast.at) } catch { /* ignore */ }
    setVisible(broadcast.message)
    const v = voice ?? DEFAULT_VOICE
    stopped.current = false
    if (broadcast.hasAudio) {
      // Recorded announcement; if it can't be fetched or played (autoplay blocked), use the voice.
      playRecording(v.repeat).catch(() => { if (!stopped.current) void speak(broadcast.message, v) })
    } else {
      void speak(broadcast.message, v)
    }
    const t = setTimeout(() => setVisible(null), SHOW_MS)
    return () => clearTimeout(t)
  }, [broadcast]) // eslint-disable-line react-hooks/exhaustive-deps

  async function playRecording(times: number) {
    const { data, contentType } = await fetchBytes(audioPath, 'kiosk', anonymous)
    const url = URL.createObjectURL(new Blob([data], { type: contentType }))
    try {
      for (let i = 0; i < Math.max(1, times) && !stopped.current; i++) {
        if (i > 0) await new Promise((r) => setTimeout(r, 1200))
        const a = new Audio(url)
        audio.current = a
        await a.play()
        await new Promise<void>((resolve) => { a.onended = () => resolve(); a.onerror = () => resolve() })
      }
    } finally {
      URL.revokeObjectURL(url)
    }
  }

  function dismiss() {
    stopped.current = true
    audio.current?.pause()
    window.speechSynthesis?.cancel()
    setVisible(null)
  }

  if (!visible) return null
  return (
    <div className="broadcast-overlay" role="alert" onClick={dismiss}>
      <div><Icon name="volume-2" size="1.6em" /><br />{visible}<div style={{ fontSize: '0.4em', marginTop: '2em', fontWeight: 400 }}>Tap to dismiss</div></div>
    </div>
  )
}

function sessionStorageGet() {
  try { return sessionStorage.getItem('smartlib.lastBroadcast') } catch { return null }
}

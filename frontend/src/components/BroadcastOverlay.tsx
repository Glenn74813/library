import { Client } from '@stomp/stompjs'
import { useEffect, useRef, useState } from 'react'
import type { KioskConfig, VoiceSettings } from '../api/types'
import { api, fetchBytes } from '../api/client'
import { speak } from '../speech'
import { Icon } from './Icon'

const SHOW_MS = 60_000
/** Announcements arrive instantly over the live connection; these checks only cover gaps. */
const POLL_CONNECTED_MS = 15_000
/** Without a live connection (blocked by a proxy, server restarting) kiosks check this often instead. */
const POLL_OFFLINE_MS = 3_000
/** Announcements older than this (by the server's clock) are not replayed on a kiosk that just started. */
const MAX_AGE_SECONDS = 10 * 60
const DEFAULT_VOICE: VoiceSettings = { name: '', lang: 'en-US', rate: 0.95, pitch: 1, repeat: 2 }

interface PublicBroadcast { broadcast: KioskConfig['broadcast']; voice: VoiceSettings; ageSeconds: number | null }

/** ws(s)://<server>/api/ws, following the API's address (or this page's, when the API is same-origin). */
function brokerUrl() {
  const base = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')
  const origin = base || window.location.origin
  return `${origin.replace(/^http/, 'ws')}/api/ws`
}

/**
 * Shows (and speaks or plays) announcements sent from the Security gate page. Each kiosk screen
 * keeps a live WebSocket (STOMP) subscription, so an announcement appears the moment it is sent,
 * with automatic reconnect; it also checks the public endpoint now and then in case a message is
 * missed. Works on every kiosk (OPAC, attendance, self-checkout), signed in or not.
 */
export function BroadcastOverlay() {
  const [current, setCurrent] = useState<PublicBroadcast | null>(null)
  const [visible, setVisible] = useState<string | null>(null)
  const [live, setLive] = useState(false)
  const lastSeen = useRef<string | null>(sessionStorageGet())
  const audio = useRef<HTMLAudioElement | null>(null)
  const stopped = useRef(false)

  // Fallback check: now and then while live, often while the live connection is down.
  useEffect(() => {
    let alive = true
    const load = () => api<PublicBroadcast>('/api/opac/broadcast', { anonymous: true, timeoutMs: 6_000 })
      .then((b) => { if (alive) setCurrent(b) })
      .catch(() => { /* offline for a moment: try again on the next tick */ })
    void load()
    const t = window.setInterval(load, live ? POLL_CONNECTED_MS : POLL_OFFLINE_MS)
    return () => { alive = false; window.clearInterval(t) }
  }, [live])

  // Live subscription with automatic reconnect.
  useEffect(() => {
    const client = new Client({
      brokerURL: brokerUrl(),
      reconnectDelay: 5_000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      debug: () => undefined,
      onConnect: () => {
        setLive(true)
        client.subscribe('/topic/announcements', (msg) => {
          try { setCurrent(JSON.parse(msg.body) as PublicBroadcast) } catch { /* ignore a malformed message */ }
        })
      },
      onWebSocketClose: () => setLive(false),
      onStompError: () => setLive(false),
    })
    client.activate()
    return () => { setLive(false); void client.deactivate() }
  }, [])

  useEffect(() => {
    const b = current?.broadcast
    if (!b || b.at === lastSeen.current) return
    lastSeen.current = b.at
    try { sessionStorage.setItem('smartlib.lastBroadcast', b.at) } catch { /* ignore */ }
    // Judge freshness by the server's clock: kiosk PCs often have the wrong time.
    if ((current?.ageSeconds ?? 0) > MAX_AGE_SECONDS) return
    setVisible(b.message)
    const v = current?.voice ?? DEFAULT_VOICE
    stopped.current = false
    if (b.hasAudio) {
      // Recorded announcement; if it can't be fetched or played (autoplay blocked), use the voice.
      playRecording(v.repeat).catch(() => { if (!stopped.current) void speak(b.message, v) })
    } else {
      void speak(b.message, v)
    }
    const t = setTimeout(() => setVisible(null), SHOW_MS)
    return () => clearTimeout(t)
  }, [current])

  async function playRecording(times: number) {
    const { data, contentType } = await fetchBytes('/api/opac/broadcast-audio', 'kiosk', true)
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

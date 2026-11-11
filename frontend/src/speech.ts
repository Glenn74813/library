import type { VoiceSettings } from './api/types'

export const speechSupported = typeof window !== 'undefined' && 'speechSynthesis' in window

/** Installed text-to-speech voices. Chrome loads them asynchronously, so wait for the list. */
export function loadVoices(): Promise<SpeechSynthesisVoice[]> {
  if (!speechSupported) return Promise.resolve([])
  const now = window.speechSynthesis.getVoices()
  if (now.length) return Promise.resolve(now)
  return new Promise((resolve) => {
    const done = () => resolve(window.speechSynthesis.getVoices())
    window.speechSynthesis.addEventListener('voiceschanged', done, { once: true })
    setTimeout(done, 2000)
  })
}

/** The named voice if this device has it, else the best match for the language, else the default. */
export function pickVoice(voices: SpeechSynthesisVoice[], s: Pick<VoiceSettings, 'name' | 'lang'>) {
  const lang = s.lang.toLowerCase()
  return voices.find((v) => v.name === s.name)
    ?? voices.find((v) => v.lang.toLowerCase() === lang)
    ?? voices.find((v) => v.lang.toLowerCase().startsWith(lang.split('-')[0]))
    ?? null
}

/** Speaks the text `repeat` times with a short pause between repetitions. */
export async function speak(text: string, s: VoiceSettings, repeat = s.repeat) {
  if (!speechSupported || !text.trim()) return
  const voice = pickVoice(await loadVoices(), s)
  const synth = window.speechSynthesis
  synth.cancel()
  for (let i = 0; i < Math.max(1, repeat); i++) {
    if (i > 0) await new Promise((r) => setTimeout(r, 1200))
    await new Promise<void>((resolve) => {
      const u = new SpeechSynthesisUtterance(text)
      if (voice) u.voice = voice
      u.lang = voice?.lang ?? s.lang
      u.rate = s.rate
      u.pitch = s.pitch
      u.onend = () => resolve()
      u.onerror = () => resolve()
      synth.speak(u)
    })
  }
}

import { useEffect } from 'react'

/** Calls `onIdle` after `ms` without touch, mouse or keyboard input (kiosk auto-reset). */
export function useInactivity(ms: number, onIdle: () => void) {
  useEffect(() => {
    let timer = window.setTimeout(onIdle, ms)
    const reset = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(onIdle, ms)
    }
    const events = ['pointerdown', 'keydown', 'touchstart', 'wheel']
    events.forEach((e) => window.addEventListener(e, reset, { passive: true }))
    return () => {
      window.clearTimeout(timer)
      events.forEach((e) => window.removeEventListener(e, reset))
    }
  }, [ms, onIdle])
}

import { createContext, useCallback, useContext, useState, type ReactNode } from 'react'

type Tone = 'success' | 'error' | 'warn' | 'info'
interface ToastItem { id: number; tone: Tone; text: string }

const ToastContext = createContext<(text: string, tone?: Tone) => void>(() => {})

let nextId = 1

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const push = useCallback((text: string, tone: Tone = 'success') => {
    const id = nextId++
    setItems((list) => [...list.slice(-2), { id, tone, text }])
    setTimeout(() => setItems((list) => list.filter((t) => t.id !== id)), tone === 'error' ? 6000 : 3500)
  }, [])
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toast-host" aria-live="polite">
        {items.map((t) => (
          <div key={t.id} className={`alert ${t.tone === 'info' ? '' : t.tone}`}>{t.text}</div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export const useToast = () => useContext(ToastContext)

export const errorText = (e: unknown) => (e instanceof Error ? e.message : String(e))

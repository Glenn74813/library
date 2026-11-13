/**
 * The RFID station on this computer: the RFID bridge program (rfid-bridge/) next to the reader, which works
 * with any reader (serial, network, vendor command-line tool, push). The library pages ask it to write a copy's
 * tag and to switch the tag's security off when the copy is borrowed and on when it comes back.
 * Set per computer (each desk or kiosk has its own reader); off unless set up.
 */
export interface StationConfig { enabled: boolean; url: string }
export interface StationStatus { ok: boolean; mode: string; reader: string; writer: string; tags: number }

const KEY = 'smartlib.rfidStation'
export const DEFAULT_STATION_URL = 'http://127.0.0.1:8765'

export function getStation(): StationConfig {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) ?? '{}') as Partial<StationConfig>
    return { enabled: !!v.enabled, url: v.url || DEFAULT_STATION_URL }
  } catch {
    return { enabled: false, url: DEFAULT_STATION_URL }
  }
}

export function setStation(c: StationConfig) {
  try { localStorage.setItem(KEY, JSON.stringify(c)) } catch { /* not saved on this computer */ }
}

async function call<T>(path: string, body?: unknown, url = getStation().url): Promise<T> {
  let res: Response
  try {
    res = await fetch(url.replace(/\/+$/, '') + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(5000),
    })
  } catch {
    throw new Error('The RFID station is not running on this computer')
  }
  const json = await res.json().catch(() => ({})) as T & { error?: string }
  if (!res.ok) throw new Error(json.error ?? `RFID station error ${res.status}`)
  return json
}

export const station = {
  status: (url?: string) => call<StationStatus>('/status', undefined, url),
  /** Tag numbers on the pad / in the reader's field now. */
  tags: () => call<{ tags: string[] }>('/tags').then((r) => r.tags),
  /** Writes the copy's barcode to the tag on the pad; returns the tag's new EPC. */
  write: (barcode: string) => call<{ epc: string }>('/write', { barcode }).then((r) => r.epc),
  security: (epc: string, secure: boolean) => call('/security', { epc, secure }),
}

/**
 * After a checkout (secure = false) or a return (secure = true): switches the copy's tag when this computer
 * has an RFID station. Returns a warning to show staff, or null when done or not set up here.
 */
export async function switchSecurity(epc: string | null | undefined, secure: boolean, barcode: string): Promise<string | null> {
  if (!getStation().enabled) return null
  if (!epc) return `${barcode}: no RFID tag number on this copy, so its security was not switched ${secure ? 'on' : 'off'}`
  try {
    await station.security(epc, secure)
    return null
  } catch (e) {
    return `${barcode}: security not switched ${secure ? 'on' : 'off'} (${(e as Error).message})`
  }
}

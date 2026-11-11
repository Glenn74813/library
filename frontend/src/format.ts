const dateFmt = new Intl.DateTimeFormat('en-PH', { year: 'numeric', month: 'short', day: 'numeric' })
const dateTimeFmt = new Intl.DateTimeFormat('en-PH', {
  year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
})
const timeFmt = new Intl.DateTimeFormat('en-PH', { hour: 'numeric', minute: '2-digit' })
const money = new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP' })

export const fmtDate = (v?: string | null) => (v ? dateFmt.format(new Date(v)) : '—')
export const fmtDateTime = (v?: string | null) => (v ? dateTimeFmt.format(new Date(v)) : '—')
export const fmtTime = (v?: string | null) => (v ? timeFmt.format(new Date(v)) : '—')
export const fmtMoney = (v?: number | null) => money.format(v ?? 0)

/** Local calendar date as yyyy-mm-dd (for <input type="date"> and API query params). */
export function isoDate(d: Date = new Date()) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export function daysAgo(n: number) {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return isoDate(d)
}

export const label = (s: string) =>
  s.toLowerCase().replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase())

export function fmtBytes(n?: number | null) {
  if (!n) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = n
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

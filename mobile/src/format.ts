const dateFmt = new Intl.DateTimeFormat('en-PH', { month: 'short', day: 'numeric', year: 'numeric' })

export const fmtDate = (v?: string | null) => (v ? dateFmt.format(new Date(v.length === 10 ? `${v}T00:00:00` : v)) : '—')

export const fmtMoney = (v?: number | null) =>
  `₱${(v ?? 0).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`

/** ON_LOAN -> "On loan" */
export const label = (code: string) => {
  const s = code.toLowerCase().replace(/_/g, ' ')
  return s.charAt(0).toUpperCase() + s.slice(1)
}

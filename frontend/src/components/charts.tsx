import { useEffect, useRef, useState, type ReactNode } from 'react'

export interface Datum { label: string; count: number }

/**
 * Categorical slice colours, in this fixed order (validated for colour-blind separation of
 * neighbouring slices, including last-next-to-first). Light slices carry visible legend labels.
 */
const SERIES = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4']

function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [width, setWidth] = useState(0)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const ro = new ResizeObserver(() => setWidth(el.clientWidth))
    ro.observe(el)
    setWidth(el.clientWidth)
    return () => ro.disconnect()
  }, [])
  return [ref, width] as const
}

/** "Nice" axis: 0..max split into 4-5 steps of 1/2/5 x 10^n. */
function niceTicks(max: number) {
  if (max <= 0) return [0, 1]
  const raw = max / 4
  const pow = 10 ** Math.floor(Math.log10(raw))
  const step = [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= raw) ?? raw
  const ticks: number[] = []
  for (let v = 0; v <= max + step * 0.001; v += step) ticks.push(Math.round(v * 1e6) / 1e6)
  if (ticks[ticks.length - 1] < max) ticks.push(ticks[ticks.length - 1] + step)
  return ticks
}

const fmtNum = (n: number) => n.toLocaleString('en-PH')

/** Card header: title + subtitle on the left, headline figure on the right. */
export function ChartHead({ title, subtitle, total, totalNote }: {
  title: string; subtitle?: string; total?: ReactNode; totalNote?: ReactNode
}) {
  return (
    <div className="chart-head">
      <div>
        <h2 className="chart-title">{title}</h2>
        {subtitle && <div className="chart-sub">{subtitle}</div>}
      </div>
      {total !== undefined && (
        <div className="chart-total">
          <div className="chart-total-value">{total}</div>
          {totalNote && <div className="chart-sub">{totalNote}</div>}
        </div>
      )}
    </div>
  )
}

/**
 * Column chart with a recessive y-axis; one column is emphasised (the latest period by default,
 * or the maximum) and every column has a hover tooltip.
 */
export function ColumnChart({ title, subtitle, data, format, tooltipLabel, emphasis = 'last', total, totalNote, unit = '' }: {
  title: string
  subtitle?: string
  data: Datum[]
  /** Axis label for a category (e.g. "11-05" -> "5"). */
  format?: (label: string) => string
  /** Tooltip heading for a category; defaults to the axis label. */
  tooltipLabel?: (label: string) => string
  emphasis?: 'last' | 'max' | 'none'
  total?: ReactNode
  totalNote?: ReactNode
  unit?: string
}) {
  const [ref, width] = useWidth<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)
  const H = 190
  const pad = { top: 10, right: 4, bottom: 26, left: 36 }
  const plotW = Math.max(0, width - pad.left - pad.right)
  const plotH = H - pad.top - pad.bottom
  const max = Math.max(0, ...data.map((d) => d.count))
  const ticks = niceTicks(max)
  const top = ticks[ticks.length - 1] || 1
  const band = data.length ? plotW / data.length : 0
  const barW = Math.max(2, Math.min(28, band * 0.62))
  const y = (v: number) => pad.top + plotH - (v / top) * plotH
  const emphasized = emphasis === 'last' ? data.length - 1
    : emphasis === 'max' ? data.reduce((best, d, i) => (d.count > data[best].count ? i : best), 0) : -1
  // Show every k-th axis label so they never collide; the emphasised one is always shown.
  const every = Math.max(1, Math.ceil(data.length / Math.max(1, Math.floor(plotW / 44))))
  const label = (d: Datum) => (format ? format(d.label) : d.label)
  const hd = hover !== null ? data[hover] : null

  return (
    <div className="chart">
      <ChartHead title={title} subtitle={subtitle} total={total} totalNote={totalNote} />
      <div className="chart-plot" ref={ref} onMouseLeave={() => setHover(null)}>
        {data.length === 0 ? <p className="muted small">No data for this period</p> : width > 0 && (
          <svg width={width} height={H} role="img" aria-label={`${title}: column chart`}>
            {ticks.map((t) => (
              <g key={t}>
                <line x1={pad.left} x2={width - pad.right} y1={y(t)} y2={y(t)} className={t === 0 ? 'chart-baseline' : 'chart-grid'} />
                <text x={pad.left - 8} y={y(t)} dy="0.32em" textAnchor="end" className="chart-tick">{fmtNum(t)}</text>
              </g>
            ))}
            {data.map((d, i) => {
              const cx = pad.left + band * i + band / 2
              const h = Math.max(0, y(0) - y(d.count))
              const r = Math.min(4, barW / 2, h)
              const x0 = cx - barW / 2
              const yTop = y(d.count)
              const em = i === emphasized
              const showLabel = em || (i % every === 0 && Math.abs(i - emphasized) >= every * 0.6)
              return (
                <g key={d.label}>
                  {h > 0 && (
                    <path className={`chart-bar ${em ? 'em' : ''} ${hover === i ? 'hover' : ''}`}
                      d={`M${x0},${y(0)} V${yTop + r} Q${x0},${yTop} ${x0 + r},${yTop} H${x0 + barW - r} Q${x0 + barW},${yTop} ${x0 + barW},${yTop + r} V${y(0)} Z`} />
                  )}
                  {showLabel && (
                    <text x={cx} y={H - 8} textAnchor="middle" className={`chart-tick ${em ? 'em' : ''}`}>{label(d)}</text>
                  )}
                  {/* Hit target is the whole column band, bigger than the bar itself. */}
                  <rect x={pad.left + band * i} y={pad.top} width={band} height={plotH} fill="transparent"
                    onMouseEnter={() => setHover(i)} onTouchStart={() => setHover(i)} />
                </g>
              )
            })}
          </svg>
        )}
        {hd && hover !== null && (
          <div className="chart-tip" style={{
            left: Math.min(Math.max(pad.left + band * hover + band / 2, 60), width - 60),
            top: Math.max(0, y(hd.count) - 8),
          }}>
            <div className="chart-tip-label">{tooltipLabel ? tooltipLabel(hd.label) : label(hd)}</div>
            <div className="chart-tip-value">{fmtNum(hd.count)}{unit}</div>
          </div>
        )}
      </div>
    </div>
  )
}

/** Pie chart with a legend that carries every slice's label, count and share. */
export function PieChart({ title, subtitle, data, unit = '' }: {
  title: string; subtitle?: string; data: Datum[]; unit?: string
}) {
  const [hover, setHover] = useState<number | null>(null)
  const slices = data.slice(0, SERIES.length)
  const total = slices.reduce((s, d) => s + d.count, 0)
  const R = 80
  const C = 90
  let angle = -Math.PI / 2
  const arcs = slices.map((d, i) => {
    const sweep = total ? (d.count / total) * Math.PI * 2 : 0
    const a0 = angle
    const a1 = angle + sweep
    angle = a1
    const lift = hover === i ? 5 : 0
    const mid = (a0 + a1) / 2
    const ox = Math.cos(mid) * lift
    const oy = Math.sin(mid) * lift
    const p = (a: number) => `${C + ox + R * Math.cos(a)},${C + oy + R * Math.sin(a)}`
    const path = sweep >= Math.PI * 2 - 1e-6
      ? `M${C - R},${C} a${R},${R} 0 1,0 ${2 * R},0 a${R},${R} 0 1,0 ${-2 * R},0`
      : `M${C + ox},${C + oy} L${p(a0)} A${R},${R} 0 ${sweep > Math.PI ? 1 : 0},1 ${p(a1)} Z`
    return { d, i, path }
  })
  const pct = (n: number) => (total ? Math.round((n / total) * 100) : 0)
  const hd = hover !== null ? slices[hover] : null

  return (
    <div className="chart">
      <ChartHead title={title} subtitle={subtitle} total={fmtNum(total)} totalNote={unit ? `total ${unit.trim()}` : 'total'} />
      {total === 0 ? <p className="muted small">No data for this period</p> : (
        <div className="pie-wrap" onMouseLeave={() => setHover(null)}>
          <div className="pie-figure">
            <svg viewBox="0 0 180 180" width="180" height="180" role="img" aria-label={`${title}: pie chart`}>
              {arcs.map(({ d, i, path }) => (
                <path key={d.label} d={path} fill={SERIES[i]} className={`pie-slice ${hover !== null && hover !== i ? 'dim' : ''}`}
                  onMouseEnter={() => setHover(i)} onTouchStart={() => setHover(i)} />
              ))}
            </svg>
            {hd && (
              <div className="chart-tip pie-tip">
                <div className="chart-tip-label">{hd.label}</div>
                <div className="chart-tip-value">{fmtNum(hd.count)}{unit} · {pct(hd.count)}%</div>
              </div>
            )}
          </div>
          <ul className="pie-legend">
            {slices.map((d, i) => (
              <li key={d.label} className={hover === i ? 'active' : ''} onMouseEnter={() => setHover(i)}>
                <span className="swatch" style={{ background: SERIES[i] }} aria-hidden />
                <span className="pie-legend-label" title={d.label}>{d.label}</span>
                <span className="pie-legend-value">{fmtNum(d.count)}</span>
                <span className="pie-legend-pct">{pct(d.count)}%</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

/** Fills missing days (yyyy-mm-dd) in [from, to] with zero so the time axis is continuous. */
export function fillDays(data: Datum[], from: string, to: string): Datum[] {
  const byDay = new Map(data.map((d) => [d.label, d.count]))
  const out: Datum[] = []
  const d = new Date(`${from}T00:00:00`)
  const end = new Date(`${to}T00:00:00`)
  for (let guard = 0; d <= end && guard < 400; guard++) {
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    out.push({ label: key, count: byDay.get(key) ?? 0 })
    d.setDate(d.getDate() + 1)
  }
  return out
}

/** Fills opening hours (default 7 AM - 7 PM, widened if visits fall outside) with zero. */
export function fillHours(data: Datum[], open = 7, close = 19): Datum[] {
  const byHour = new Map(data.map((d) => [Number(d.label), d.count]))
  const hours = [...byHour.keys()]
  const start = Math.min(open, ...hours)
  const end = Math.max(close, ...hours)
  const out: Datum[] = []
  for (let h = start; h <= end; h++) out.push({ label: String(h), count: byHour.get(h) ?? 0 })
  return out
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
/** "2026-11-05" -> "Nov 5" */
export const dayLabel = (iso: string) => `${MONTHS[Number(iso.slice(5, 7)) - 1]} ${Number(iso.slice(8, 10))}`
/** "2026-11-05" -> "5" (axis) */
export const dayTick = (iso: string) => String(Number(iso.slice(8, 10)))
/** "14" -> "2 PM" */
export const hourLabel = (h: string) => {
  const n = Number(h)
  return `${n % 12 === 0 ? 12 : n % 12} ${n < 12 ? 'AM' : 'PM'}`
}

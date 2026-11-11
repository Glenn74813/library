import type { CSSProperties } from 'react'

/**
 * Feather icons, read from the repository's `feather/` folder at build time. Only the icons listed
 * here are bundled; add a file name below (and to IconName) to use another one.
 */
const files = import.meta.glob<string>([
  '@feather/alert-octagon.svg', '@feather/alert-triangle.svg', '@feather/bar-chart-2.svg', '@feather/book.svg',
  '@feather/book-open.svg', '@feather/bookmark.svg', '@feather/briefcase.svg', '@feather/calendar.svg',
  '@feather/chevron-left.svg', '@feather/chevron-right.svg', '@feather/clock.svg', '@feather/credit-card.svg',
  '@feather/delete.svg', '@feather/download.svg', '@feather/external-link.svg', '@feather/file-text.svg',
  '@feather/key.svg', '@feather/log-out.svg', '@feather/menu.svg', '@feather/monitor.svg', '@feather/package.svg',
  '@feather/plus.svg', '@feather/printer.svg', '@feather/repeat.svg', '@feather/search.svg', '@feather/settings.svg',
  '@feather/shield.svg', '@feather/trending-up.svg', '@feather/type.svg', '@feather/upload.svg', '@feather/user.svg',
  '@feather/users.svg', '@feather/volume-2.svg', '@feather/x.svg', '@feather/eye.svg', '@feather/link.svg',
  '@feather/trash-2.svg', '@feather/zoom-in.svg', '@feather/zoom-out.svg', '@feather/arrow-left.svg',
  '@feather/tablet.svg', '@feather/lock.svg', '@feather/bell.svg', '@feather/check-circle.svg',
  '@feather/music.svg', '@feather/play.svg', '@feather/mic.svg', '@feather/camera.svg',
], { query: '?raw', import: 'default', eager: true })

export type IconName =
  | 'alert-octagon' | 'alert-triangle' | 'bar-chart-2' | 'book' | 'book-open' | 'bookmark' | 'briefcase'
  | 'calendar' | 'chevron-left' | 'chevron-right' | 'clock' | 'credit-card' | 'delete' | 'download'
  | 'external-link' | 'file-text' | 'key' | 'log-out' | 'menu' | 'monitor' | 'package' | 'plus' | 'printer'
  | 'repeat' | 'search' | 'settings' | 'shield' | 'trending-up' | 'type' | 'upload' | 'user' | 'users'
  | 'volume-2' | 'x' | 'eye' | 'link' | 'trash-2' | 'zoom-in' | 'zoom-out' | 'arrow-left' | 'tablet' | 'lock' | 'bell' | 'check-circle' | 'music' | 'play' | 'mic' | 'camera'

// name -> inner SVG markup (paths, circles, ...) so we can render with our own size and class.
const ICONS: Record<string, string> = {}
for (const [path, svg] of Object.entries(files)) {
  const name = /([\w-]+)\.svg$/.exec(path)?.[1]
  if (name) ICONS[name] = svg.replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '')
}

export function Icon({ name, size, className, style, label }: {
  name: IconName
  /** CSS size; defaults to 1.15em so icons follow the surrounding text size. */
  size?: number | string
  className?: string
  style?: CSSProperties
  /** Accessible label; omit for decorative icons next to text. */
  label?: string
}) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}
      strokeLinecap="round" strokeLinejoin="round" className={`icon ${className ?? ''}`}
      style={size !== undefined ? { width: size, height: size, ...style } : style}
      role={label ? 'img' : undefined} aria-label={label} aria-hidden={label ? undefined : true}
      dangerouslySetInnerHTML={{ __html: ICONS[name] ?? '' }} />
  )
}

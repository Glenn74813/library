/**
 * The library server the app talks to. Set EXPO_PUBLIC_API_URL when building to point at another server
 * (e.g. the campus server); otherwise the hosted library site is used.
 */
export const API_URL = (process.env.EXPO_PUBLIC_API_URL ?? 'https://zscmst-library.pg1992-gp.workers.dev').replace(/\/+$/, '')

/** The web portal, for things the app hands over to it (reading e-books, resetting a PIN). */
export const WEB_URL = (process.env.EXPO_PUBLIC_WEB_URL ?? API_URL).replace(/\/+$/, '')

export const LIBRARY_NAME = 'ZSCMST Learning Resource Center'

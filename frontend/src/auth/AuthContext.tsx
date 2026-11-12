import { createContext, useContext, useSyncExternalStore, type ReactNode } from 'react'
import { getDeviceKey, getSession, subscribe, type Session } from '../api/client'

const AuthContext = createContext<Session | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const session = useSyncExternalStore(subscribe, () => getSession('user'))
  return <AuthContext.Provider value={session}>{children}</AuthContext.Provider>
}

export function useSession() {
  return useContext(AuthContext)
}

/** The My Library (patron portal) session, kept apart from the staff session. */
export function usePatronSession() {
  return useSyncExternalStore(subscribe, () => getSession('patron'))
}

export function useKioskSession() {
  return useSyncExternalStore(subscribe, () => getSession('kiosk'))
}

/** True when this kiosk can talk to the server: it has a device key, or (older setup) a kiosk session. */
export function useKioskConnected() {
  return useSyncExternalStore(subscribe, () => !!getDeviceKey() || !!getSession('kiosk'))
}

export function hasRole(session: Session | null, ...roles: string[]) {
  return !!session && session.user.roles.some((r) => roles.includes(r))
}

export const STAFF_ROLES = ['ADMIN', 'LIBRARIAN', 'STAFF']

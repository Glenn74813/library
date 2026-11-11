import { createContext, useContext, useSyncExternalStore, type ReactNode } from 'react'
import { getSession, subscribe, type Session } from '../api/client'

const AuthContext = createContext<Session | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const session = useSyncExternalStore(subscribe, () => getSession('user'))
  return <AuthContext.Provider value={session}>{children}</AuthContext.Provider>
}

export function useSession() {
  return useContext(AuthContext)
}

export function useKioskSession() {
  return useSyncExternalStore(subscribe, () => getSession('kiosk'))
}

export function hasRole(session: Session | null, ...roles: string[]) {
  return !!session && session.user.roles.some((r) => roles.includes(r))
}

export const STAFF_ROLES = ['ADMIN', 'LIBRARIAN', 'STAFF']

import { useEffect, useState } from 'react'
import { api } from './client'

/** Library name, contact details and limits from Administration → System settings. */
export interface LibraryProfile {
  systemName: string
  name: string
  institution: string
  shortName: string
  address: string
  phone: string
  email: string
  website: string
  receiptHeader: string
  receiptFooter: string
}

export interface PublicConfig {
  profile: LibraryProfile
  opacResetSeconds: number
  uploads: { ebookMaxMb: number; audioMaxMb: number; photoMaxMb: number }
}

/** Used until the server answers, and if it can't be reached (kiosks keep working offline). */
export const DEFAULT_CONFIG: PublicConfig = {
  profile: {
    systemName: 'ZSCMST Smart Library', name: 'ZSCMST Learning Resource Center',
    institution: 'Zamboanga State College of Marine Sciences and Technology', shortName: 'ZSCMST',
    address: '', phone: '', email: '', website: '', receiptHeader: '', receiptFooter: '',
  },
  opacResetSeconds: 90,
  uploads: { ebookMaxMb: 100, audioMaxMb: 10, photoMaxMb: 5 },
}

let cached: PublicConfig | null = null
let loading: Promise<PublicConfig> | null = null
const listeners = new Set<(c: PublicConfig) => void>()

function load(force = false): Promise<PublicConfig> {
  if (cached && !force) return Promise.resolve(cached)
  if (!loading || force) {
    loading = api<PublicConfig>('/api/opac/profile', { anonymous: true })
      .then((c) => {
        cached = c
        document.title = c.profile.systemName
        listeners.forEach((l) => l(c))
        return c
      })
      .catch(() => cached ?? DEFAULT_CONFIG)
      .finally(() => { loading = null })
  }
  return loading
}

/** Reloads the settings everywhere after an administrator saves them. */
export const reloadLibraryConfig = () => load(true)

export function useLibraryConfig(): PublicConfig {
  const [config, setConfig] = useState<PublicConfig>(cached ?? DEFAULT_CONFIG)
  useEffect(() => {
    listeners.add(setConfig)
    void load().then(setConfig)
    return () => { listeners.delete(setConfig) }
  }, [])
  return config
}

export const useLibrary = () => useLibraryConfig().profile

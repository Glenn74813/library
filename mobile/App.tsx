import { useEffect, useState, useSyncExternalStore } from 'react'
import { ActivityIndicator, Pressable, Text, View } from 'react-native'
import { StatusBar } from 'expo-status-bar'
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context'
import { api, getSession, restoreSession, subscribe } from './src/api'
import { FinesScreen } from './src/screens/FinesScreen'
import { HoldsScreen } from './src/screens/HoldsScreen'
import { HomeScreen } from './src/screens/HomeScreen'
import { IdScreen } from './src/screens/IdScreen'
import { LoginScreen } from './src/screens/LoginScreen'
import { SearchScreen } from './src/screens/SearchScreen'
import type { PatronSummary } from './src/types'
import { Button, colors, Notice } from './src/ui'
import { useLoad } from './src/useLoad'

type Tab = 'home' | 'holds' | 'search' | 'fines' | 'id'
const TABS: { id: Tab; label: string; title: string }[] = [
  { id: 'home', label: 'Loans', title: 'My Library' },
  { id: 'holds', label: 'Reserved', title: 'Reservations' },
  { id: 'search', label: 'Search', title: 'Search & reserve' },
  { id: 'fines', label: 'Fines', title: 'Fines' },
  { id: 'id', label: 'My ID', title: 'My library ID' },
]

export default function App() {
  const [ready, setReady] = useState(false)
  const session = useSyncExternalStore(subscribe, getSession)
  useEffect(() => { void restoreSession().finally(() => setReady(true)) }, [])

  return (
    <SafeAreaProvider>
      <StatusBar style="light" />
      {!ready ? <View style={{ flex: 1, backgroundColor: colors.navy, justifyContent: 'center' }}><ActivityIndicator color="#fff" /></View>
        : session ? <Portal key={session.user.patronId ?? 0} /> : <LoginScreen />}
    </SafeAreaProvider>
  )
}

/** Signed in: the patron's summary feeds the tabs; each tab loads its own details. */
function Portal() {
  const [tab, setTab] = useState<Tab>('home')
  const { data: me, error, loading, reload } = useLoad(() => api<PatronSummary>('/api/me'))
  const ready = me?.holds.filter((h) => h.status === 'READY').length ?? 0

  return (
    <SafeAreaView edges={['top']} style={{ flex: 1, backgroundColor: colors.navy }}>
      <View style={{ paddingHorizontal: 16, paddingVertical: 12 }}>
        <Text style={{ color: '#fff', fontSize: 20, fontWeight: '800' }}>{TABS.find((t) => t.id === tab)!.title}</Text>
      </View>
      <View style={{ flex: 1, backgroundColor: colors.bg }}>
        {!me ? (
          <View style={{ flex: 1, justifyContent: 'center', padding: 16, gap: 12 }}>
            {error ? <><Notice tone="bad" text={error} /><Button title="Try again" onPress={reload} /></> : <ActivityIndicator color={colors.navy} />}
          </View>
        ) : <>
          {tab === 'home' && <HomeScreen me={me} refreshing={loading} onReload={reload} />}
          {tab === 'holds' && <HoldsScreen onChanged={reload} />}
          {tab === 'search' && <SearchScreen onReserved={() => { void reload(); setTab('holds') }} />}
          {tab === 'fines' && <FinesScreen />}
          {tab === 'id' && <IdScreen patron={me.patron} />}
        </>}
      </View>
      <SafeAreaView edges={['bottom']} style={{ backgroundColor: '#fff', borderTopWidth: 1, borderTopColor: colors.border }}>
        <View style={{ flexDirection: 'row' }} accessibilityRole="tablist">
          {TABS.map((t) => {
            const active = t.id === tab
            const count = t.id === 'holds' ? ready : t.id === 'fines' && me?.unpaidTotal ? '!' : 0
            return (
              <Pressable key={t.id} accessibilityRole="tab" accessibilityState={{ selected: active }} onPress={() => setTab(t.id)}
                style={{ flex: 1, alignItems: 'center', paddingVertical: 10 }}>
                <View style={{ width: 28, height: 4, borderRadius: 2, marginBottom: 6, backgroundColor: active ? colors.navy : 'transparent' }} />
                <Text style={{ fontSize: 13, fontWeight: active ? '800' : '600', color: active ? colors.navy : colors.muted }}>
                  {t.label}{count ? ` (${count})` : ''}</Text>
              </Pressable>
            )
          })}
        </View>
      </SafeAreaView>
    </SafeAreaView>
  )
}

import { useState } from 'react'
import { Alert, FlatList, Text, TextInput, View } from 'react-native'
import { api, errorText } from '../api'
import { label } from '../format'
import type { BibRecord, Page } from '../types'
import { Badge, Button, Card, colors, Empty, Muted, Notice, s } from '../ui'

/** Search the catalog and reserve a title; the library sets a copy aside and the patron is told when it is ready. */
export function SearchScreen({ onReserved }: { onReserved: () => void }) {
  const [input, setInput] = useState('')
  const [results, setResults] = useState<BibRecord[] | null>(null)
  const [searching, setSearching] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<number | null>(null)

  async function search() {
    const q = input.trim()
    if (!q) return
    setSearching(true)
    setError(null)
    try {
      const page = await api<Page<BibRecord>>(`/api/opac/search?q=${encodeURIComponent(q)}&size=30`, { anonymous: true })
      setResults(page.content)
    } catch (e) {
      setError(errorText(e))
    } finally {
      setSearching(false)
    }
  }

  async function reserve(r: BibRecord) {
    setBusy(r.id)
    try {
      await api('/api/me/holds', { body: { recordId: r.id } })
      Alert.alert('Reserved', `We will tell you when "${r.title}" is ready for pickup.`)
      onReserved()
    } catch (e) {
      Alert.alert('Could not reserve', errorText(e))
    } finally {
      setBusy(null)
    }
  }

  return (
    <FlatList
      data={results ?? []}
      keyExtractor={(r) => String(r.id)}
      contentContainerStyle={{ padding: 16, gap: 12 }}
      keyboardShouldPersistTaps="handled"
      ListHeaderComponent={
        <View style={{ gap: 10, marginBottom: 4 }}>
          <View style={{ flexDirection: 'row', gap: 8 }}>
            <TextInput value={input} onChangeText={setInput} onSubmitEditing={search} returnKeyType="search" placeholder="Title, author, subject…"
              placeholderTextColor={colors.muted} style={[s.input, { flex: 1 }]} autoCorrect={false} />
            <Button kind="primary" title="Search" onPress={search} busy={searching} />
          </View>
          {error && <Notice tone="bad" text={error} />}
        </View>
      }
      ListEmptyComponent={results ? <Empty text="No titles found" /> : <Empty text="Search the library catalog to reserve a book" />}
      renderItem={({ item: r }) => (
        <Card>
          <Text style={s.title}>{r.title}</Text>
          <Muted>{[r.authors, r.publicationYear, label(r.materialType)].filter(Boolean).join(' · ')}</Muted>
          {r.callNumber && <Muted>Call number {r.callNumber}</Muted>}
          <Badge tone={r.availableCopies ? 'ok' : 'warn'} text={r.totalCopies === 0 ? 'No copies' : `${r.availableCopies} of ${r.totalCopies} available`} />
          {r.totalCopies > 0 && <Button small title="Reserve" onPress={() => void reserve(r)} busy={busy === r.id} disabled={busy !== null} />}
        </Card>
      )}
    />
  )
}

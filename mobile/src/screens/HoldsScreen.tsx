import { Alert, RefreshControl, ScrollView, Text } from 'react-native'
import { api, errorText } from '../api'
import { fmtDate } from '../format'
import type { Hold } from '../types'
import { Badge, Button, Card, Empty, Muted, Notice, s } from '../ui'
import { useLoad } from '../useLoad'

const STATUS: Record<Hold['status'], { text: string; tone: 'ok' | 'info' | 'warn' | 'bad' }> = {
  READY: { text: 'Ready for pickup', tone: 'ok' },
  PENDING: { text: 'Waiting', tone: 'info' },
  SCHEDULED: { text: 'Booked', tone: 'info' },
  FULFILLED: { text: 'Picked up', tone: 'ok' },
  CANCELLED: { text: 'Cancelled', tone: 'warn' },
  EXPIRED: { text: 'Expired', tone: 'bad' },
}

/** The patron's reservations: where each stands, and cancelling one. */
export function HoldsScreen({ onChanged }: { onChanged: () => void }) {
  const { data, error, loading, reload } = useLoad(() => api<Hold[]>('/api/me/holds'))

  function cancel(h: Hold) {
    Alert.alert('Cancel reservation?', h.title, [
      { text: 'Keep it', style: 'cancel' },
      { text: 'Cancel reservation', style: 'destructive', onPress: async () => {
        try {
          await api(`/api/me/holds/${h.id}`, { method: 'DELETE' })
          void reload()
          onChanged()
        } catch (e) { Alert.alert('Could not cancel', errorText(e)) }
      } },
    ])
  }

  return (
    <ScrollView contentContainerStyle={{ padding: 16, gap: 14 }} refreshControl={<RefreshControl refreshing={loading} onRefresh={reload} />}>
      {error && <Notice tone="bad" text={error} />}
      {data?.length === 0 && <Card><Empty text="No reservations. Find a title under Search to reserve it." /></Card>}
      {data?.map((h) => (
        <Card key={h.id}>
          <Text style={s.title}>{h.title}</Text>
          <Badge tone={STATUS[h.status].tone} text={STATUS[h.status].text} />
          <Muted>{h.status === 'READY' ? `Pick it up at the circulation desk by ${fmtDate(h.expiresAt)}`
            : h.status === 'SCHEDULED' ? `Ready on ${fmtDate(h.pickupDate)}`
            : h.queuePosition ? `#${h.queuePosition} in line · reserved ${fmtDate(h.placedAt)}` : `Reserved ${fmtDate(h.placedAt)}`}</Muted>
          {(h.status === 'PENDING' || h.status === 'READY' || h.status === 'SCHEDULED') &&
            <Button small kind="danger" title="Cancel reservation" onPress={() => cancel(h)} />}
        </Card>
      ))}
    </ScrollView>
  )
}

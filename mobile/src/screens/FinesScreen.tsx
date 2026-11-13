import { RefreshControl, ScrollView, Text, View } from 'react-native'
import { api } from '../api'
import { fmtDate, fmtMoney } from '../format'
import type { Fine } from '../types'
import { Badge, Card, Empty, Muted, Notice, s } from '../ui'
import { useLoad } from '../useLoad'

/** Fines and how they were settled. Payment is made at the library, not in the app. */
export function FinesScreen() {
  const { data, error, loading, reload } = useLoad(() => api<Fine[]>('/api/me/fines'))
  const unpaid = (data ?? []).filter((f) => f.status === 'UNPAID').reduce((t, f) => t + f.amount, 0)
  return (
    <ScrollView contentContainerStyle={{ padding: 16, gap: 14 }} refreshControl={<RefreshControl refreshing={loading} onRefresh={reload} />}>
      {error && <Notice tone="bad" text={error} />}
      {data && <Notice tone={unpaid > 0 ? 'bad' : 'ok'} text={unpaid > 0
        ? `You owe ${fmtMoney(unpaid)}. Pay at the library's cashier. Items with unpaid fines can only be returned at the desk.`
        : 'You have no unpaid fines.'} />}
      {data?.length === 0 && <Card><Empty text="No fines" /></Card>}
      {data?.map((f) => (
        <Card key={f.id}>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: 10 }}>
            <Text style={[s.title, { flex: 1 }]}>{f.reason}</Text>
            <Text style={s.title}>{fmtMoney(f.amount)}</Text>
          </View>
          <Badge tone={f.status === 'UNPAID' ? 'bad' : f.status === 'PAID' ? 'ok' : 'info'}
            text={f.status === 'UNPAID' ? 'Unpaid' : f.status === 'PAID' ? 'Paid' : 'Waived'} />
          <Muted>{fmtDate(f.createdAt)}{f.receiptNumber ? ` · receipt ${f.receiptNumber}` : ''}</Muted>
        </Card>
      ))}
    </ScrollView>
  )
}

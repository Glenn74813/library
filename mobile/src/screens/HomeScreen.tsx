import { useState } from 'react'
import { Alert, RefreshControl, ScrollView, Text, View } from 'react-native'
import { api, errorText } from '../api'
import { fmtDate, fmtMoney } from '../format'
import type { PatronSummary, RenewalRequest } from '../types'
import { Badge, Button, Card, colors, Empty, H2, Muted, Notice, s, Stat } from '../ui'
import { useLoad } from '../useLoad'

/** Greeting, what needs attention, and the patron's borrowed items with renewals. */
export function HomeScreen({ me, refreshing, onReload }: { me: PatronSummary; refreshing: boolean; onReload: () => void }) {
  const renewals = useLoad(() => api<RenewalRequest[]>('/api/me/renewals'))
  const pending = new Set((renewals.data ?? []).filter((r) => r.status === 'PENDING').map((r) => r.loanId))
  const [busy, setBusy] = useState<number | null>(null)
  const overdue = me.loans.filter((l) => l.overdue).length
  const ready = me.holds.filter((h) => h.status === 'READY')

  async function renew(loanId: number) {
    setBusy(loanId)
    try {
      const r = await api<{ pending: boolean }>(`/api/me/loans/${loanId}/renew`, { method: 'POST' })
      Alert.alert(r.pending ? 'Renewal requested' : 'Renewed', r.pending
        ? 'The library will approve or reject it. Keep the item until you hear back.' : 'Your new due date is shown in the list.')
      onReload()
      void renewals.reload()
    } catch (e) {
      Alert.alert('Could not renew', errorText(e))
    } finally {
      setBusy(null)
    }
  }

  return (
    <ScrollView contentContainerStyle={{ padding: 16, gap: 14 }}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { onReload(); void renewals.reload() }} />}>
      <View>
        <Text style={{ fontSize: 24, fontWeight: '800', color: colors.text }}>Hi, {me.patron.firstName}!</Text>
        <Muted>{me.patron.cardNumber} · {me.patron.categoryName}{me.patron.courseCode ? ` · ${me.patron.courseCode}` : ''}</Muted>
      </View>
      {me.blocks.map((b) => <Notice key={b} tone="warn" text={b} />)}
      {ready.length > 0 && <Notice tone="ok" text={`${ready.length} reservation${ready.length === 1 ? ' is' : 's are'} ready for pickup at the circulation desk.`} />}
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
        <Stat label="Borrowed" value={`${me.loans.length}/${me.maxLoans}`} />
        <Stat label="Overdue" value={overdue} tone={overdue ? 'bad' : undefined} />
        <Stat label="Ready for pickup" value={ready.length} />
        <Stat label="Fines due" value={fmtMoney(me.unpaidTotal)} tone={me.unpaidTotal > 0 ? 'bad' : undefined} />
      </View>

      <H2>My loans</H2>
      {me.loans.length === 0 && <Card><Empty text="You have no borrowed items" /></Card>}
      {me.loans.map((l) => (
        <Card key={l.id}>
          <Text style={s.title}>{l.title}</Text>
          {l.callNumber && <Muted>{l.callNumber}</Muted>}
          <View style={{ flexDirection: 'row', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <Text style={{ fontSize: 15, color: colors.text }}>Due <Text style={{ fontWeight: '700' }}>{fmtDate(l.dueAt)}</Text></Text>
            {l.overdue && <Badge tone="bad" text={`Overdue ${l.overdueDays} day${l.overdueDays === 1 ? '' : 's'}`} />}
          </View>
          {l.overdue && !!l.accruedFine && <Muted>Fine so far: {fmtMoney(l.accruedFine)}. Return it at the desk.</Muted>}
          <Muted>Renewed {l.renewals} of {l.maxRenewals} times</Muted>
          {pending.has(l.id) ? <Badge text="Renewal requested" />
            : <Button small title="Renew" onPress={() => void renew(l.id)} busy={busy === l.id}
                disabled={l.overdue || l.renewals >= l.maxRenewals || busy !== null} />}
        </Card>
      ))}

      {renewals.data && renewals.data.length > 0 && <>
        <H2>Renewal requests</H2>
        {renewals.data.map((r) => (
          <Card key={r.id}>
            <Text style={s.title}>{r.title}</Text>
            <Badge tone={r.status === 'APPROVED' ? 'ok' : r.status === 'REJECTED' ? 'bad' : 'info'} text={r.status === 'PENDING' ? 'Waiting for the library'
              : r.status === 'APPROVED' ? 'Approved' : r.status === 'REJECTED' ? 'Rejected' : 'Cancelled'} />
            <Muted>{r.status === 'APPROVED' ? `Now due ${fmtDate(r.newDueAt)}`
              : r.status === 'REJECTED' ? `Reason: ${r.reason ?? '—'} · return by ${fmtDate(r.dueAt)}`
              : r.status === 'PENDING' ? `Asked ${fmtDate(r.requestedAt)} · keep the item until you hear back` : r.reason ?? ''}</Muted>
          </Card>
        ))}
      </>}
    </ScrollView>
  )
}

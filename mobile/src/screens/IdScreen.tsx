import { useState } from 'react'
import { Alert, Image, Linking, ScrollView, Text, View } from 'react-native'
import Svg, { Rect } from 'react-native-svg'
import { absolute, api, errorText, signOut } from '../api'
import { code128 } from '../code128'
import { LIBRARY_NAME, WEB_URL } from '../config'
import { fmtDate } from '../format'
import type { Patron } from '../types'
import { Button, Card, colors, Field, H2, Muted, Notice } from '../ui'

/** The patron's library ID with a scannable barcode, plus PIN change and sign-out. */
export function IdScreen({ patron }: { patron: Patron }) {
  const expired = !!patron.expiryDate && patron.expiryDate < new Date().toISOString().slice(0, 10)
  return (
    <ScrollView contentContainerStyle={{ padding: 16, gap: 14 }}>
      <View style={{ borderRadius: 16, overflow: 'hidden', borderWidth: 1, borderColor: colors.border, backgroundColor: '#fff' }}>
        <View style={{ backgroundColor: colors.navy, padding: 12, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
          <Text style={{ color: '#fff', fontWeight: '700', flex: 1 }} numberOfLines={1}>{LIBRARY_NAME}</Text>
          <Text style={{ color: '#C9CAF0', fontWeight: '700', fontSize: 12 }}>LIBRARY ID</Text>
        </View>
        <View style={{ flexDirection: 'row', gap: 14, padding: 14 }}>
          {patron.photoUrl
            ? <Image source={{ uri: absolute(patron.photoUrl) }} style={{ width: 90, height: 112, borderRadius: 8, backgroundColor: colors.bg }} accessibilityLabel="Your ID photo" />
            : <View style={{ width: 90, height: 112, borderRadius: 8, backgroundColor: colors.bg, alignItems: 'center', justifyContent: 'center' }}>
                <Text style={{ color: colors.muted, fontSize: 12 }}>No photo</Text></View>}
          <View style={{ flex: 1, gap: 6 }}>
            <Text style={{ fontSize: 17, fontWeight: '800', color: colors.text }}>{patron.lastName.toUpperCase()}, {patron.firstName}</Text>
            <Small label="ID NO." value={patron.cardNumber} />
            <Small label="TYPE" value={`${patron.categoryName}${patron.courseCode ? ` · ${patron.courseCode}` : ''}`} />
            {patron.expiryDate && <Small label="VALID UNTIL" value={fmtDate(patron.expiryDate)} />}
          </View>
        </View>
        <View style={{ alignItems: 'center', paddingBottom: 14, paddingHorizontal: 14 }}>
          <Barcode value={patron.cardNumber} />
          <Text style={{ fontFamily: 'monospace', fontWeight: '700', marginTop: 4, color: colors.text }}>{patron.cardNumber}</Text>
        </View>
      </View>
      {(expired || patron.blocked) && <Notice tone="warn" text={`${expired ? 'Your library ID has expired. ' : ''}${patron.blocked ? 'Your account is on hold. ' : ''}Please see the circulation desk.`} />}
      <Muted>Show this screen at the kiosk or desk; turn your screen brightness up if the scanner doesn't read it. Your official
        library ID is issued at the circulation desk, and your photo is taken there or in the web portal.</Muted>

      <ChangePin />

      <Card>
        <H2>More in the web portal</H2>
        <Muted>Read the school's e-books, book a pickup date, update your photo, or download a copy of your data.</Muted>
        <Button title="Open the web portal" onPress={() => void Linking.openURL(`${WEB_URL}/my`)} />
      </Card>

      <Button kind="danger" title="Sign out" onPress={() => Alert.alert('Sign out?', undefined, [
        { text: 'Stay signed in', style: 'cancel' }, { text: 'Sign out', style: 'destructive', onPress: () => void signOut() }])} />
    </ScrollView>
  )
}

function Small({ label, value }: { label: string; value: string }) {
  return (
    <View>
      <Text style={{ fontSize: 11, color: colors.muted }}>{label}</Text>
      <Text style={{ fontSize: 14, fontWeight: '700', color: colors.text }}>{value}</Text>
    </View>
  )
}

/** Code 128 bars drawn as SVG: quiet zones either side, 2 px per module, black on white. */
function Barcode({ value }: { value: string }) {
  let widths: number[]
  try { widths = code128(value) } catch { return null }
  const module = 2
  const quiet = 10 * module
  const total = widths.reduce((a, b) => a + b, 0) * module + quiet * 2
  let x = quiet
  const bars = widths.map((w, i) => {
    const rect = i % 2 === 0 ? <Rect key={i} x={x} y={0} width={w * module} height={70} fill="#000" /> : null
    x += w * module
    return rect
  })
  return (
    <View style={{ backgroundColor: '#fff', width: '100%', alignItems: 'center' }} accessibilityLabel={`Barcode of library ID ${value}`}>
      <Svg width="100%" height={70} viewBox={`0 0 ${total} 70`} preserveAspectRatio="xMidYMid meet">
        <Rect x={0} y={0} width={total} height={70} fill="#fff" />
        {bars}
      </Svg>
    </View>
  )
}

function ChangePin() {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [busy, setBusy] = useState(false)

  async function save() {
    if (!/^\d{4,8}$/.test(next)) return Alert.alert('New PIN', 'Use 4 to 8 digits.')
    setBusy(true)
    try {
      await api('/api/me/pin', { body: { currentPin: current, newPin: next } })
      Alert.alert('PIN changed', 'Please sign in again with your new PIN.')
      await signOut()
    } catch (e) {
      Alert.alert('Could not change PIN', errorText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <H2>Change PIN</H2>
      <Field label="Current PIN" value={current} onChangeText={setCurrent} secureTextEntry keyboardType="number-pad" />
      <Field label="New PIN (4 to 8 digits)" value={next} onChangeText={setNext} secureTextEntry keyboardType="number-pad" />
      <Button kind="primary" title="Change PIN" onPress={save} busy={busy} disabled={!current || !next} />
    </Card>
  )
}

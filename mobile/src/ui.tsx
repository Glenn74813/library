import type { ReactNode } from 'react'
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View, type TextInputProps, type ViewStyle } from 'react-native'

export const colors = {
  navy: '#0E0E78',
  navySoft: '#E8E8F6',
  bg: '#F4F5F9',
  card: '#FFFFFF',
  text: '#1B1B2A',
  muted: '#6B6F80',
  border: '#DCDDE6',
  ok: '#137A3D',
  okSoft: '#E3F4EA',
  warn: '#8A5A00',
  warnSoft: '#FDF1D8',
  bad: '#B42318',
  badSoft: '#FDE7E5',
}

export function Card({ children, style }: { children: ReactNode; style?: ViewStyle }) {
  return <View style={[s.card, style]}>{children}</View>
}

export function H2({ children }: { children: ReactNode }) {
  return <Text style={s.h2}>{children}</Text>
}

export function Muted({ children, style }: { children: ReactNode; style?: object }) {
  return <Text style={[s.muted, style]}>{children}</Text>
}

export function Button({ title, onPress, kind = 'default', disabled, busy, small }: {
  title: string; onPress: () => void; kind?: 'primary' | 'default' | 'danger'; disabled?: boolean; busy?: boolean; small?: boolean
}) {
  const primary = kind === 'primary'
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ disabled: !!disabled || !!busy }} disabled={disabled || busy} onPress={onPress}
      style={({ pressed }) => [s.btn, small && s.btnSmall, primary && s.btnPrimary, kind === 'danger' && s.btnDanger,
        (disabled || busy) && { opacity: 0.5 }, pressed && { opacity: 0.75 }]}>
      {busy ? <ActivityIndicator color={primary ? '#fff' : colors.navy} />
        : <Text style={[s.btnText, small && { fontSize: 14 }, primary && { color: '#fff' }, kind === 'danger' && { color: colors.bad }]}>{title}</Text>}
    </Pressable>
  )
}

export function Field({ label, ...input }: { label: string } & TextInputProps) {
  return (
    <View style={{ gap: 6 }}>
      <Text style={s.label}>{label}</Text>
      <TextInput placeholderTextColor={colors.muted} style={s.input} {...input} />
    </View>
  )
}

type Tone = 'ok' | 'warn' | 'bad' | 'info'
const TONES: Record<Tone, { bg: string; fg: string }> = {
  ok: { bg: colors.okSoft, fg: colors.ok },
  warn: { bg: colors.warnSoft, fg: colors.warn },
  bad: { bg: colors.badSoft, fg: colors.bad },
  info: { bg: colors.navySoft, fg: colors.navy },
}

export function Badge({ text, tone = 'info' }: { text: string; tone?: Tone }) {
  return <View style={[s.badge, { backgroundColor: TONES[tone].bg }]}><Text style={[s.badgeText, { color: TONES[tone].fg }]}>{text}</Text></View>
}

export function Notice({ text, tone = 'info' }: { text: string; tone?: Tone }) {
  return <View style={[s.notice, { backgroundColor: TONES[tone].bg }]}><Text style={{ color: TONES[tone].fg, fontSize: 14 }}>{text}</Text></View>
}

export function Stat({ label, value, tone }: { label: string; value: string | number; tone?: 'bad' }) {
  return (
    <View style={s.stat}>
      <Text style={[s.statValue, tone === 'bad' && { color: colors.bad }]}>{value}</Text>
      <Text style={s.statLabel}>{label}</Text>
    </View>
  )
}

/** A label and value on one line, label muted and value bold. */
export function Row({ label, value }: { label: string; value: ReactNode }) {
  return (
    <View style={s.row}>
      <Text style={s.rowLabel}>{label}</Text>
      {typeof value === 'string' || typeof value === 'number' ? <Text style={s.rowValue}>{value}</Text> : value}
    </View>
  )
}

export function Empty({ text }: { text: string }) {
  return <Text style={[s.muted, { textAlign: 'center', paddingVertical: 24 }]}>{text}</Text>
}

export const s = StyleSheet.create({
  card: { backgroundColor: colors.card, borderRadius: 14, padding: 16, gap: 10, borderWidth: 1, borderColor: colors.border },
  h2: { fontSize: 18, fontWeight: '700', color: colors.text },
  muted: { color: colors.muted, fontSize: 14 },
  label: { fontSize: 14, fontWeight: '600', color: colors.text },
  input: { borderWidth: 1, borderColor: colors.border, borderRadius: 10, paddingHorizontal: 14, paddingVertical: 12, fontSize: 16,
    backgroundColor: '#fff', color: colors.text },
  btn: { borderWidth: 1, borderColor: colors.border, borderRadius: 10, paddingVertical: 12, paddingHorizontal: 16, alignItems: 'center',
    justifyContent: 'center', backgroundColor: '#fff', minHeight: 46 },
  btnSmall: { paddingVertical: 8, paddingHorizontal: 12, minHeight: 38 },
  btnPrimary: { backgroundColor: colors.navy, borderColor: colors.navy },
  btnDanger: { borderColor: colors.bad },
  btnText: { fontSize: 16, fontWeight: '600', color: colors.navy },
  badge: { alignSelf: 'flex-start', borderRadius: 999, paddingHorizontal: 10, paddingVertical: 3 },
  badgeText: { fontSize: 12, fontWeight: '700' },
  notice: { borderRadius: 10, padding: 12 },
  stat: { flex: 1, minWidth: '45%', backgroundColor: colors.card, borderRadius: 14, padding: 14, borderWidth: 1, borderColor: colors.border },
  statValue: { fontSize: 22, fontWeight: '800', color: colors.text },
  statLabel: { fontSize: 13, color: colors.muted, marginTop: 2 },
  row: { flexDirection: 'row', justifyContent: 'space-between', gap: 12 },
  rowLabel: { color: colors.muted, fontSize: 14 },
  rowValue: { color: colors.text, fontSize: 14, fontWeight: '700', flexShrink: 1, textAlign: 'right' },
  title: { fontSize: 16, fontWeight: '700', color: colors.text },
})

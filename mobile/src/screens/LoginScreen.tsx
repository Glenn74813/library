import { useState } from 'react'
import { Image, KeyboardAvoidingView, Linking, Platform, ScrollView, Text, View } from 'react-native'
import { errorText, signIn } from '../api'
import { LIBRARY_NAME, WEB_URL } from '../config'
import { Button, Card, colors, Field, Muted, Notice } from '../ui'

export function LoginScreen() {
  const [card, setCard] = useState('')
  const [pin, setPin] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit() {
    if (!card.trim() || !pin) return setError('Enter your library ID and PIN')
    setBusy(true)
    setError(null)
    try {
      await signIn(card, pin)
    } catch (e) {
      setError(errorText(e))
      setPin('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1, backgroundColor: colors.navy }}>
      <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', padding: 20, gap: 20 }} keyboardShouldPersistTaps="handled">
        <View style={{ alignItems: 'center', gap: 8 }}>
          <Image source={require('../../assets/icon.png')} style={{ width: 72, height: 72, borderRadius: 16 }} />
          <Text style={{ color: '#fff', fontSize: 26, fontWeight: '800' }}>My Library</Text>
          <Text style={{ color: '#C9CAF0', fontSize: 14, textAlign: 'center' }}>{LIBRARY_NAME}</Text>
        </View>
        <Card>
          <Field label="Library ID" value={card} onChangeText={setCard} placeholder="e.g. 2024-0001" autoCapitalize="characters"
            autoCorrect={false} autoComplete="username" textContentType="username" returnKeyType="next" />
          <Field label="PIN" value={pin} onChangeText={setPin} placeholder="4 to 8 digits" secureTextEntry keyboardType="number-pad"
            autoComplete="password" textContentType="password" onSubmitEditing={submit} />
          {error && <Notice tone="bad" text={error} />}
          <Button kind="primary" title="Sign in" onPress={submit} busy={busy} />
          <Button title="Forgot your PIN?" onPress={() => void Linking.openURL(`${WEB_URL}/my/forgot-pin`)} />
          <Muted>No library ID or PIN yet? Ask at the circulation desk.</Muted>
        </Card>
      </ScrollView>
    </KeyboardAvoidingView>
  )
}

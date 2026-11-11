import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import type { Patron } from '../api/types'
import { Icon } from './Icon'
import { errorText } from './Toast'
import { ErrorAlert, Modal } from './ui'

/** Upload a patron photo or take one with the desk webcam. */
export function PatronPhotoModal({ patron, onClose, onSaved }: { patron: Patron; onClose: () => void; onSaved: () => void }) {
  const [mode, setMode] = useState<'choose' | 'camera'>('choose')
  const [shot, setShot] = useState<{ blob: Blob; url: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const video = useRef<HTMLVideoElement>(null)
  const stream = useRef<MediaStream | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)

  useEffect(() => () => { stream.current?.getTracks().forEach((t) => t.stop()) }, [])

  async function startCamera() {
    setError(null)
    try {
      const s = await navigator.mediaDevices.getUserMedia({ video: { width: 640, height: 640, facingMode: 'user' } })
      stream.current = s
      setMode('camera')
      setTimeout(() => { if (video.current) { video.current.srcObject = s; void video.current.play() } }, 0)
    } catch {
      setError('No camera available (or access was blocked). Upload a photo file instead.')
    }
  }

  function capture() {
    const v = video.current
    if (!v) return
    const size = Math.min(v.videoWidth, v.videoHeight)
    const canvas = document.createElement('canvas')
    canvas.width = 480
    canvas.height = 480
    canvas.getContext('2d')!.drawImage(v, (v.videoWidth - size) / 2, (v.videoHeight - size) / 2, size, size, 0, 0, 480, 480)
    canvas.toBlob((b) => b && setShot({ blob: b, url: URL.createObjectURL(b) }), 'image/jpeg', 0.88)
    stream.current?.getTracks().forEach((t) => t.stop())
  }

  async function save(blob: Blob, name: string) {
    const fd = new FormData()
    fd.append('file', blob, name)
    setBusy(true)
    try {
      await api(`/api/patrons/${patron.id}/photo`, { body: fd })
      onSaved()
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    try { await api(`/api/patrons/${patron.id}/photo`, { method: 'DELETE' }); onSaved() } catch (e) { setError(errorText(e)) }
  }

  return (
    <Modal title={`Photo — ${patron.fullName}`} onClose={onClose} footer={<>
      {patron.photoUrl && !shot && <button className="btn danger" onClick={remove}>Remove photo</button>}
      <span className="spacer" />
      <button className="btn" onClick={onClose}>Cancel</button>
      {shot && <button className="btn primary" disabled={busy} onClick={() => save(shot.blob, `${patron.cardNumber}.jpg`)}>{busy ? 'Saving…' : 'Save photo'}</button>}
    </>}>
      <div className="stack" style={{ justifyItems: 'center' }}>
        <ErrorAlert error={error} />
        <input ref={fileRef} type="file" hidden accept="image/jpeg,image/png,image/webp"
          onChange={(e) => { const f = e.target.files?.[0]; if (f) void save(f, f.name) }} />
        {shot ? <img src={shot.url} alt="New photo" className="photo-preview" />
          : mode === 'camera' ? <video ref={video} muted playsInline className="photo-preview" />
            : patron.photoUrl ? <img src={patron.photoUrl} alt="Current photo" className="photo-preview" />
              : <div className="photo-preview photo-empty"><Icon name="user" size={64} /></div>}
        <div className="row" style={{ justifyContent: 'center' }}>
          {mode === 'camera' && !shot && <button className="btn primary" onClick={capture}><Icon name="camera" />Take photo</button>}
          {shot && <button className="btn" onClick={() => { setShot(null); void startCamera() }}>Retake</button>}
          {mode === 'choose' && <>
            <button className="btn" onClick={() => fileRef.current?.click()} disabled={busy}><Icon name="upload" />Upload file</button>
            <button className="btn" onClick={startCamera}><Icon name="camera" />Use webcam</button>
          </>}
        </div>
      </div>
    </Modal>
  )
}

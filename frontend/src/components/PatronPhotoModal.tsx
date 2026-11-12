import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import type { Patron } from '../api/types'
import { Icon } from './Icon'
import { errorText } from './Toast'
import { ErrorAlert, Modal } from './ui'

/**
 * Upload a patron photo or take one with the camera. Staff use the patron's endpoint; patrons
 * set their own card photo in My Library (`endpoint="/api/me/photo"`).
 */
export function PatronPhotoModal({ patron, onClose, onSaved, endpoint = `/api/patrons/${patron.id}/photo`, title = `Photo — ${patron.fullName}` }: {
  patron: Patron; onClose: () => void; onSaved: () => void; endpoint?: string; title?: string
}) {
  const [mode, setMode] = useState<'choose' | 'camera'>('choose')
  /** The new photo, shown for checking before it is saved. */
  const [shot, setShot] = useState<{ blob: Blob; url: string; name: string; source: 'camera' | 'file' } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const video = useRef<HTMLVideoElement>(null)
  const stream = useRef<MediaStream | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)

  /** False once closed: a camera that is granted after that is switched straight off. */
  const alive = useRef(true)

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; stream.current?.getTracks().forEach((t) => t.stop()) }
  }, [])

  async function startCamera() {
    setError(null)
    try {
      const s = await navigator.mediaDevices.getUserMedia({ video: { width: 640, height: 640, facingMode: 'user' } })
      if (!alive.current) { s.getTracks().forEach((t) => t.stop()); return }
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
    canvas.toBlob((b) => b && setShot({ blob: b, url: URL.createObjectURL(b), name: `${patron.cardNumber}.jpg`, source: 'camera' }),
      'image/jpeg', 0.88)
    stream.current?.getTracks().forEach((t) => t.stop())
  }

  async function save(blob: Blob, name: string) {
    const fd = new FormData()
    fd.append('file', blob, name)
    setBusy(true)
    try {
      await api(endpoint, { body: fd })
      onSaved()
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  function picked(f: File) {
    stream.current?.getTracks().forEach((t) => t.stop())
    setMode('choose')
    setError(null)
    setShot({ blob: f, url: URL.createObjectURL(f), name: f.name, source: 'file' })
  }

  function chooseFile() {
    if (fileRef.current) {
      fileRef.current.value = '' // so choosing the same file again still counts as a change
      fileRef.current.click()
    }
  }

  function retake() {
    setShot(null)
    void startCamera()
  }

  async function remove() {
    try { await api(endpoint, { method: 'DELETE' }); onSaved() } catch (e) { setError(errorText(e)) }
  }

  return (
    <Modal title={title} onClose={onClose} footer={<>
      {patron.photoUrl && !shot && <button className="btn danger" onClick={remove}>Remove photo</button>}
      <span className="spacer" />
      <button className="btn" onClick={onClose}>Cancel</button>
    </>}>
      <div className="stack" style={{ justifyItems: 'center' }}>
        <ErrorAlert error={error} />
        <input ref={fileRef} type="file" hidden accept="image/jpeg,image/png,image/webp"
          onChange={(e) => { const f = e.target.files?.[0]; if (f) picked(f) }} />
        {shot ? <img src={shot.url} alt="New photo" className="photo-preview" />
          : mode === 'camera' ? <video ref={video} muted playsInline className="photo-preview" />
            : patron.photoUrl ? <img src={patron.photoUrl} alt="Current photo" className="photo-preview" />
              : <div className="photo-preview photo-empty"><Icon name="user" size={64} /></div>}
        <div className="row" style={{ justifyContent: 'center' }}>
          {mode === 'camera' && !shot && <>
            <button className="btn primary" onClick={capture}><Icon name="camera" />Take photo</button>
            <button className="btn" onClick={chooseFile}><Icon name="upload" />Upload a file instead</button>
          </>}
          {shot && <>
            {shot.source === 'camera'
              ? <button className="btn" onClick={retake} disabled={busy}><Icon name="camera" />Retake</button>
              : <button className="btn" onClick={chooseFile} disabled={busy}><Icon name="upload" />Re-upload</button>}
            <button className="btn primary" onClick={() => save(shot.blob, shot.name)} disabled={busy}>
              <Icon name="check" />{busy ? 'Saving…' : 'Save photo'}</button>
          </>}
          {mode === 'choose' && !shot && <>
            <button className="btn" onClick={chooseFile} disabled={busy}><Icon name="upload" />Upload file</button>
            <button className="btn" onClick={startCamera}><Icon name="camera" />Take a photo</button>
          </>}
        </div>
        {shot && <p className="muted small" style={{ margin: 0 }}>Check the photo. Nothing is saved until you press Save photo.</p>}
      </div>
    </Modal>
  )
}

import { useEffect, useRef, useState } from 'react'
import { Icon } from './Icon'
import { Modal } from './ui'

/**
 * Camera button for barcode/QR scanning with a phone or webcam (for librarians away from a USB
 * scanner, and for stocktake on the floor). Reads Code 128, Code 39, EAN-13 (ISBN) and QR.
 * The camera needs HTTPS (or localhost).
 */
export function CameraScanButton({ onScan, title = 'Scan with camera' }: { onScan: (text: string) => void; title?: string }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button type="button" className="btn" title={title} aria-label={title} onClick={() => setOpen(true)}><Icon name="camera" /></button>
      {open && <CameraScanModal onClose={() => setOpen(false)} onScan={(t) => { setOpen(false); onScan(t) }} />}
    </>
  )
}

function CameraScanModal({ onScan, onClose }: { onScan: (text: string) => void; onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null)
  const [error, setError] = useState<string | null>(null)
  // The parent passes a new function on every render; keep the latest without restarting the camera.
  const onScanRef = useRef(onScan)
  useEffect(() => { onScanRef.current = onScan })

  useEffect(() => {
    let stop: (() => void) | null = null
    let done = false
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      setError('The camera only works over HTTPS. Use the USB scanner or type the code.')
      return
    }
    // Loaded on demand so the scanner library isn't part of every page.
    void import('@zxing/browser').then(async ({ BrowserMultiFormatReader }) => {
      if (done || !video.current) return
      try {
        const reader = new BrowserMultiFormatReader()
        const controls = await reader.decodeFromConstraints(
          { video: { facingMode: { ideal: 'environment' } } }, video.current,
          (result) => {
            if (result && !done) {
              done = true
              controls.stop()
              navigator.vibrate?.(80)
              onScanRef.current(result.getText().trim())
            }
          })
        stop = () => controls.stop()
        if (done) stop()
      } catch (e) {
        const denied = e instanceof DOMException && (e.name === 'NotAllowedError' || e.name === 'SecurityError')
        setError(denied ? 'Camera access was blocked. Allow the camera for this site and try again.'
          : 'No camera could be started on this device.')
      }
    })
    return () => { done = true; stop?.() }
  }, [])

  return (
    <Modal title="Scan barcode or QR code" onClose={onClose}>
      <div className="stack">
        {error ? <div className="alert error">{error}</div> : (
          <>
            <div className="camera-box">
              <video ref={video} muted playsInline />
              <div className="camera-aim" aria-hidden />
            </div>
            <p className="muted small" style={{ margin: 0, textAlign: 'center' }}>Point the camera at the barcode; it is read automatically.</p>
          </>
        )}
      </div>
    </Modal>
  )
}

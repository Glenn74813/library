import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import { Logo } from '../../components/Logo'
import { ErrorAlert } from '../../components/ui'
import { useAsync } from '../../hooks/useAsync'
import { useLibrary } from '../../api/library'

interface Notice { text: string; officer: string; contact: string }

/** Public privacy notice (Data Privacy Act, RA 10173), linked from the OPAC, kiosk and patron sign-in. */
export function PrivacyNotice() {
  const lib = useLibrary()
  const navigate = useNavigate()
  const { data, error } = useAsync(() => api<Notice>('/api/opac/privacy', { anonymous: true }), [])
  return (
    <div className="center-page">
      <div className="card stack" style={{ maxWidth: 720, width: '100%' }}>
        <div style={{ textAlign: 'center' }}><Logo size={72} /></div>
        <h1 style={{ margin: 0, textAlign: 'center' }}>Privacy notice</h1>
        <div className="muted small" style={{ textAlign: 'center' }}>{lib.name} · Data Privacy Act of 2012 (RA 10173)</div>
        {error && <ErrorAlert error={error} />}
        {data && <>
          {data.text.split(/\n{2,}/).map((para, i) => <p key={i} style={{ margin: 0, whiteSpace: 'pre-line' }}>{para}</p>)}
          {(data.officer || data.contact) && (
            <p style={{ margin: 0 }}><strong>Questions or requests:</strong> {data.officer}{data.officer && data.contact && ' — '}
              {data.contact.includes('@') ? <a href={`mailto:${data.contact}`}>{data.contact}</a> : data.contact}</p>
          )}
        </>}
        <div className="row end"><button className="btn" onClick={() => (window.history.length > 1 ? navigate(-1) : navigate('/my/login'))}>Back</button></div>
      </div>
    </div>
  )
}

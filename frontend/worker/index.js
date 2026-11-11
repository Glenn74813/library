/**
 * Serves the built app and forwards /api/* to the Spring Boot backend, so the browser sees a
 * single address (no CORS, and stored links such as /api/opac/photos/... keep working).
 * Set BACKEND_URL in the Cloudflare dashboard, e.g. https://library-api.example.org
 */
export default {
  async fetch(request, env) {
    const url = new URL(request.url)
    if (!url.pathname.startsWith('/api/')) return env.ASSETS.fetch(request)

    if (!env.BACKEND_URL) {
      return Response.json({ status: 503, title: 'Service Unavailable', detail: 'BACKEND_URL is not set for this Worker' },
        { status: 503, headers: { 'Content-Type': 'application/problem+json' } })
    }
    const target = new URL(url.pathname + url.search, env.BACKEND_URL)
    const headers = new Headers(request.headers)
    // To the browser this is the same site, so it isn't a cross-origin call for the backend either.
    headers.delete('Origin')
    headers.set('X-Forwarded-Host', url.host)
    headers.set('X-Forwarded-Proto', 'https')
    return fetch(target, {
      method: request.method,
      headers,
      body: request.method === 'GET' || request.method === 'HEAD' ? undefined : request.body,
      redirect: 'manual',
    })
  },
}

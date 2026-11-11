# ZSCMST Smart Library

Library Management System for the ZSCMST Learning Resource Center, built from the "Smart Library"
purchase request (PR 2409-007): LMS, OPAC kiosks, attendance kiosk, RFID security gate integration
and a patron self-service app.

| Layer    | Stack |
|----------|-------|
| Backend  | Java 25, Spring Boot 4.1, Spring Security (JWT resource server), Spring Data JPA, Flyway |
| Database | PostgreSQL 18 |
| Frontend | React 19 + TypeScript, Vite, React Router — responsive from phones to 27" kiosks |
| Icons    | Feather SVGs from `feather/` (bundled at build time via `frontend/src/components/Icon.tsx`) |
| Deploy   | Docker Compose: `db`, `api`, `web` (nginx) as separate, independently scalable services |

```
smart-library/
├── backend/     Spring Boot REST API (stateless, horizontally scalable)
├── frontend/    React SPA (static files, served by nginx or any CDN)
├── docs/        User manual, admin manual, UAT checklist, kiosk lockdown guide
├── scripts/     mvn.sh, seed_demo.py, backup.sh, restore.sh
└── docker-compose.yml
```

## Quick start (Docker)

```bash
cp .env.example .env        # then edit JWT_SECRET, DB_PASSWORD, ADMIN_PASSWORD
docker compose up -d --build
```

- Web app: http://localhost:8081 — sign in as `admin` / `ADMIN_PASSWORD` and **change the password**.
- API: http://localhost:8080/api, health at `/actuator/health`.
- PostgreSQL is published on host port `5433` (override with `DB_HOST_PORT`).
- SIP2 (self-check / RFID stations) listens on TCP `6001`. Keep it on the library LAN.

Manuals: [user](docs/user-manual.md), [administrator](docs/admin-manual.md),
[UAT checklist](docs/uat-checklist.md), [kiosk lockdown](docs/kiosk-lockdown.md).

## Local development

```bash
docker compose up -d db                                    # PostgreSQL on :5433
cd backend && DB_URL=jdbc:postgresql://localhost:5433/smartlibrary mvn spring-boot:run   # needs JDK 25
# or without JDK 25 installed:  scripts/mvn.sh spring-boot:run -DDB_URL=...
cd frontend && npm install && npm run dev                  # http://localhost:5173 (proxies /api to :8080)
```

Demo data (20 Nicholas Sparks novels with copies, 20 patrons with PIN `1234`, loans, holds, visits):

```bash
scripts/seed_demo.py --api http://localhost:8080 --user admin --password <admin password>
```

Tests: `scripts/mvn.sh test`, `cd frontend && npm run build && npx eslint src`.

## Screens

| URL | Who | Purpose |
|-----|-----|---------|
| `/staff` | Admin / Librarian / Staff | Dashboard, circulation desk, catalog, patrons, holds, fines, attendance, gate, stocktake, reports, users, audit |
| `/opac` (`/opac?kiosk=1` on kiosks) | Public | Catalog search with live availability; kiosk mode adds on-screen keyboard and auto-reset |
| `/kiosk/attendance` | KIOSK device | Time-in/out by RFID, barcode or virtual keyboard; stand-by info board/video; offline queue |
| `/my` | Patrons | Loans, renewals, reservations, fines, PIN change, download own data (the "mobile app" as a responsive web app) |
| `/privacy` | Public | Privacy notice (Data Privacy Act, RA 10173) |

## Administration (staff app → Administration)

| Page | What it controls |
|------|------------------|
| Loan rules & courses | Per-category loan period, item limit, renewals, **max reservations**, fine per day with **grace period** and **cap per item**, unpaid-fines limit, **pickup window**; per material type **room use only** and **loan period**; booking horizon, at-risk warning, default lost-item charge; programs/courses |
| Library calendar | Weekly opening hours and closed days/holidays. Due dates and pickup deadlines skip closed days, fines count only open days, closed days can't be booked, open visits are timed out at closing |
| Notifications | SMTP e-mail and SMS gateway, daily send time, due-soon lead time, message templates, test send, delivery log |
| Catalog fields | Extra fields on every record (optionally shown in the OPAC) |
| Users & devices | Staff users, kiosks and gates; **sign-in protection** (lock after N failed attempts) and unlock |
| System settings | Library profile (names, address, contact, receipt header/footer), kiosk timers, sign-in session lengths, upload limits |
| Audit trail | Every action, searchable by text, user, action and dates; CSV/PDF export |
| Data privacy | Privacy notice and DPO contact, **retention limits** applied nightly (with preview); per-patron data export and anonymization are on each patron's page |
| Security gate | Emergency mode, alarm and alarm schedule, announcement presets (text, uploaded or recorded audio), announcement voice |

## Backups

The `backup` service writes `backups/db-*.dump` (PostgreSQL) and `backups/files-*.tar.gz` (uploaded
e-books and recordings) every day at `BACKUP_HOUR` (default 02:00) and keeps `BACKUP_KEEP_DAYS`
(default 14). **Copy the `backups/` folder to another machine or drive regularly.**

```bash
docker compose exec backup /bin/sh /backup.sh once        # take a backup now
scripts/restore.sh backups/db-<stamp>.dump backups/files-<stamp>.tar.gz   # restore (asks for confirmation)
```

## HTTPS

Required for real use (logins, and the microphone on the Security gate page). The optional Caddy
service terminates TLS in front of the web app:

```bash
# public DNS name -> free Let's Encrypt certificate
DOMAIN=library.zscmst.edu.ph TLS=admin@zscmst.edu.ph docker compose --profile https up -d
# school LAN without a public name -> Caddy's own certificate (install its root CA on library PCs)
DOMAIN=library.lan TLS=internal docker compose --profile https up -d
```

Then set `CORS_ALLOWED_ORIGINS=https://<DOMAIN>` in `.env`.

## Security model

- **Access tokens**: HS256 JWT, 15 min (`JWT_ACCESS_TTL`), roles in the `roles` claim.
- **Refresh tokens**: opaque, single-use and rotated on every refresh, stored as SHA-256 hashes.
  Re-using an old refresh token revokes all sessions of that account. Password/PIN change or
  disabling a user revokes their refresh tokens.
- **Roles**: `ADMIN`, `LIBRARIAN` (overrides, waivers, settings), `STAFF`, `KIOSK`, `GATE`, `PATRON`.
- BCrypt password/PIN hashing, audit trail (`/staff/audit`), CORS restricted by `CORS_ALLOWED_ORIGINS`.
- Kiosk PCs sign in once with a KIOSK account and refresh their own session.
  Gate middleware uses a long-lived device token (Users & devices → Issue device token); **Revoke all tokens**
  or disabling the device stops its tokens immediately.

## E-books

A title is either **school-owned** or **commercial**, never both:

| | School-owned | Commercial |
|---|---|---|
| Examples | Theses, school publications, materials the school holds rights to | Publisher e-books (e.g. Nicholas Sparks) |
| Librarian | Catalog record → *E-book / digital copy* → **Upload** PDF or EPUB (max 100 MB) | Same panel → **Link a commercial e-book** (vendor address + provider) |
| Stored here | Yes, in the `files` volume (`STORAGE_DIR`), outside the web root | No file is ever uploaded or served |
| Patrons | Any signed-in patron reads it in the built-in reader (`/read/:id`) on phone, tablet or PC | Opens on the vendor's platform under the vendor's terms |

- Files are recognised by content (a renamed file is rejected), served only through authenticated
  endpoints with `Cache-Control: no-store`, and each opening is counted for usage statistics.
- The in-browser reader has no download button, but it is not DRM; only upload titles the school may share.
- **Back up the `files` volume together with the database.**

## Hardware integration

- **Barcode scanners / RFID card readers / UHF desk pads** in keyboard-wedge mode work everywhere
  there is a scan box (circulation desk, stocktake, attendance kiosk).
- **RFID security gate**: the gate or its middleware calls
  - `POST /api/gate/device/check` `{gateId, epcs:[...]}` → `{alarm, emergencyMode, tags}`
    (real-time borrowed/unborrowed check; alarms are logged and suppressed in emergency mode)
  - `POST /api/gate/device/events` `{gateId, type: TAMPER|POWER_LOSS|POWER_RESTORED|VISITOR|HEARTBEAT, visitorCount, message}`
  - `GET /api/gate/device/config`
- **SIP2 server** (TCP 6001) for self-check kiosks, book drops and RFID staff stations: login, status,
  patron status/information, item information, checkout, checkin, renew, end session, resend, with
  optional checksums. Stations sign in with a KIOSK account; see the admin manual.
- **Offline circulation desk**: when the server can't be reached, the desk queues checkouts and
  returns in the browser and uploads them later with their original times (`POST /api/circulation/offline-sync`).
- **Notices**: overdue / due-soon / hold-ready notices are generated daily from editable templates
  and sent by e-mail (SMTP) and/or SMS gateway as configured under Administration → Notifications.

## Not included (needs vendor hardware, specs or a school decision)

- NCIP and a native Z39.50 client (copy cataloging uses Open Library and the Library of Congress SRU service).
- Writing RFID tags (AFI/EAS security bit) — done by the tagging station's vendor software; the
  library system only stores each copy's EPC.
- Native Android/iOS apps (the patron portal is a responsive web app; can be wrapped as a PWA).
- Choosing and configuring the school's real SMTP mailbox and SMS provider (Administration → Notifications).
- The retention periods themselves (Administration → Data privacy) are a school policy decision; all default to "keep".

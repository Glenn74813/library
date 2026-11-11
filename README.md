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
├── scripts/     mvn.sh — run Maven with JDK 25 in Docker
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
| `/my` | Patrons | Loans, renewals, reservations, fines, PIN change (the "mobile app" as a responsive web app) |

## Security model

- **Access tokens**: HS256 JWT, 15 min (`JWT_ACCESS_TTL`), roles in the `roles` claim.
- **Refresh tokens**: opaque, single-use and rotated on every refresh, stored as SHA-256 hashes.
  Re-using an old refresh token revokes all sessions of that account. Password/PIN change or
  disabling a user revokes their refresh tokens.
- **Roles**: `ADMIN`, `LIBRARIAN` (overrides, waivers, settings), `STAFF`, `KIOSK`, `GATE`, `PATRON`.
- BCrypt password/PIN hashing, audit trail (`/staff/audit`), CORS restricted by `CORS_ALLOWED_ORIGINS`.
- Kiosk PCs sign in once with a KIOSK account and refresh their own session.
  Gate middleware uses a long-lived device token (Users & devices → Issue device token); to revoke one, rotate `JWT_SECRET`.

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
- **Notices**: overdue / due-soon / hold-ready notices are generated daily from editable templates
  (`app_setting` keys `notice.template.*`). The default sender logs them; add a `NoticeSender`
  bean for e-mail (JavaMailSender) or an SMS gateway.

## Not included yet (needs vendor hardware/specs)

- SIP2 / NCIP server and Z39.50 client (ISBN copy-cataloging uses Open Library's REST API today).
- Native Android/iOS apps (the patron portal is a responsive web app; can be wrapped as a PWA).
- MARC21 import/export and the migration of existing library records.
- Email/SMS delivery provider configuration.

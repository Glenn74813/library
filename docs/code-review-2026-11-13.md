# Code review and change inventory — 13 November 2026

Scope: the whole repository (≈28,000 lines): Java backend (15k), React frontend (11k), Expo patron app,
RFID bridge, Docker/Cloudflare deployment. Four independent reviews (business logic, security, performance and
maintainability, frontend/mobile/bridge); every High and most Medium findings were then re-checked against the
code. Paths are relative to `backend/src/main/java/ph/edu/zscmst/library/` unless they say otherwise.

**Priorities:** **P1 = fix before go-live** (security hole, wrong money or loan records), **P2 = should fix**
(will hurt as data grows or under concurrent use), **P3 = nice to have** (maintainability).

**Status (13 Nov 2026):** all P1 items below are fixed (S1–S7, C1–C7, F1–F5, D1), with new tests for C1, C6, C7 and the
pay-with-return path. Still open: P2 and P3, and changing the live database password (it is still the example value;
the port is now reachable from the server PC only).

---

## P1 — fix before go-live

### Security

| # | Issue | Where | Fix |
|---|---|---|---|
| S1 | **RFID bridge obeys any website.** It only uses CORS to decide whether to *show* the answer, but always *executes* the request. Any page open in a desk/kiosk browser can switch a tag's security off (`/security`), rewrite tags (`/write`), inject fake reads (`/push`) or, on a gate PC, send events with the gate's token (`/event`). `origins` defaults to `*`. | `rfid-bridge/RfidBridge.java:296-336` | Refuse (403) requests whose Origin isn't on an allow-list defaulting to the library site; require `Content-Type: application/json` (forces a preflight); add a per-station secret header. |
| S2 | **Bridge argument injection.** Request values (`epc`, `barcode`) are pasted into the vendor command *before* it is split into arguments, so `{"epc":"X --erase --all"}` adds arguments, and with a `cmd /c` template can run any command. | `RfidBridge.java:271-275` | Validate `epc` (`^[0-9A-F]{16,32}$`) and `barcode`; split the template first, then fill each placeholder inside its own argument. |
| S3 | **SIP2 login: unlimited password guessing, admins allowed, cleartext, published port.** Message 93 accepts ADMIN/LIBRARIAN/STAFF/KIOSK accounts, never counts failures, and says whether the password was right. Docker publishes 6001 on all interfaces. | `sip2/SipHandler.java:59, 269-276`; `docker-compose.yml:41` | Use `LoginThrottle`; allow only KIOSK (or a SIP role); bind 6001 to the LAN/localhost; TLS via stunnel if used across the network. |
| S4 | **SIP2 patron PIN oracle.** PIN checks (`CQ`, checkout, renew) are unlimited, an empty PIN counts as correct, and patron information returns e-mail and phone. | `SipHandler.java:224-227, 300-303, 328-337, 369, 435` | Count PIN failures in `LoginThrottle` (patron); require the PIN when the station is configured to; omit BE/BF. |
| S5 | **Default admin password `Admin@12345`** is used if `ADMIN_PASSWORD` isn't set, and the account isn't forced to change it. | `application.yml:54`; `docker-compose.yml:33`; `user/AdminBootstrap.java:33` | Make `ADMIN_PASSWORD` required (`:?`) and set `mustChangePassword` on the bootstrap admin. |
| S6 | **Database and API ports open on every interface; default DB password.** Postgres on `0.0.0.0:5433` with `smartlibrary`/`smartlibrary` gives the whole patron database to anyone on the LAN. Port 8080 bypasses Cloudflare. | `docker-compose.yml:11,15,31,39`; `application.yml:7` | Remove or bind to `127.0.0.1` the 5433 and 8080 mappings; make `DB_PASSWORD` required. |
| S7 | **Default JWT secret in `application.yml`.** Compose refuses to start without one, but `java -jar` or another deployment would sign tokens with a public key → anyone can forge an admin token. | `application.yml:45`; `security/SecurityConfig.java:91` | Remove the default; refuse known placeholder values at start-up. |

### Loans, fines and money

| # | Issue | Where | Fix |
|---|---|---|---|
| C1 | **A late offline or SIP2 return can close someone else's newer loan.** Offline return at 10:00, copy lent to patron B at 11:00, desk syncs at 12:00 → B's loan is closed and the book is "back". | `circulation/CirculationService.java:252-272` | If the offline return time is before the active loan's checkout, don't apply it; report it for staff. |
| C2 | **Checkout's row lock reads stale data.** The copy and patron are loaded first, then locked; Hibernate returns the already-loaded object, so the status checks run on old data. Two desks, or a desk and a reservation, can both take the same copy (hold left READY on a lent copy; later a 500 on return). | `CirculationService.java:105-110` | Resolve the code to an id without loading the entity, then lock; or `refresh(…, PESSIMISTIC_WRITE)` after locking. |
| C3 | **Returns take no lock → overdue fine charged twice** on a double-click or desk + kiosk at once; with PAY, two receipts. | `CirculationService.java:227-275` | Lock the copy, then re-read the active loan. |
| C4 | **Waiving can overwrite a payment** made at the same moment: fine ends WAIVED, cash collected, receipt no longer lists it. | `CirculationService.java:807-814` | Lock the fine in `waive` and re-check UNPAID. |
| C5 | **SIP2 returns skip "settle fines before return".** | `sip2/SipHandler.java:411` | Same check as the web kiosk (`previewCheckin` total > 0 → "settle at the desk"). |
| C6 | **`checkinSettled` waives (or crashes) without a choice.** If a fine appears between the preview and the return, `settle=null` goes down the waive path (NullPointerException without a reason). | `CirculationService.java:236-245` | After the return, open fines with no PAY/WAIVE choice → 409; waive only on WAIVE. |
| C7 | **Closing a day after a loan was made still fines that day.** Due Friday, Friday then declared closed, returned Monday → charged 1 day. Breaks "due dates move to the next open day". | `calendar/CalendarController.java:103,132`; fine count `CirculationService.java:426` | Count overdue days from `nextOpenOnOrAfter(due)` (simplest), or move affected due dates when a closed day is added. |

### Desk, kiosk and app

| # | Issue | Where | Fix |
|---|---|---|---|
| F1 | **Attendance kiosk records duplicate taps.** A slow answer (>6 s) is queued as offline although the server saved it; the sync strips the tap id, so the server can't spot repeats. In toggle mode the repeat becomes a time-out. | `frontend/src/pages/kiosk/AttendanceKiosk.tsx:79, 126-137` | Send the tap id on live taps and syncs; server ignores ids already seen. |
| F2 | **Offline desk transactions never switch RFID security**: a book lent offline sets the gate off; a book returned offline stays disarmed. | `frontend/src/pages/staff/CirculationPage.tsx:246, 444`; `api/offlineCirculation.ts` | Return `rfidEpc` in the sync result and switch security after upload; list failures. |
| F3 | **Self-check reset during a checkout leaves the tag armed** (loan saved, gate alarms on a properly borrowed book). | `frontend/src/pages/kiosk/SelfCheckKiosk.tsx:171-179` | Always switch security after a successful checkout; hold the idle reset while scans are pending. |
| F4 | **Desk returns can be lost**: switching tabs while the settle-fines window is open stops every later scan from being sent, silently. | `CirculationPage.tsx:419` | Resolve the pending promise on unmount and move remaining scans to the offline queue or log. |
| F5 | **Any failed token refresh signs out**, including 502/503 during an outage — staff lose the desk while it is in offline mode; app users are signed out on a server restart. | `frontend/src/api/client.ts:141`; `mobile/src/api.ts:85` | Clear the session only on 400/401/403. |

### Database

| # | Issue | Where | Fix |
|---|---|---|---|
| D1 | **Missing indexes**: none on `loan.checkout_at / returned_at / due_at`, `fine.loan_id / status / created_at / settled_at`, `attendance_log` open visits, `gate_event.item_id`, `stocktake_scan.item_id`. The dashboard and every return scan whole tables; it gets slower every month. | `db/migration/V1__init.sql` and later | One new migration (V27) adding these indexes. Cheap, no code change. |

---

## P2 — should fix

**Security and privacy**
- A librarian can redirect the stored SMTP password / SMS key to their own server (change host, keep stored secret, press Send a test); errors leak connection details. `notice/NotificationController.java:88-127` → clear the secret when host/URL changes; ADMIN only; generic error.
- Public OPAC reserve endpoints reveal which library IDs exist (404 vs 403/423) and allow PIN spraying and mass lock-outs from the internet; staff login has the same username leak. `selfcheck/OpacReserveController.java:68-97`, `security/AuthController.java:85-124` → same answer for unknown/locked; per-IP limit (Cloudflare rule); 6-digit PINs.
- Attendance kiosk tap returns full name, card number, program and photo for any code, and `/sync` accepts back-dated taps. `attendance/AttendanceController.java:69-72`, `AttendanceService.java:90-130` → first name and photo only; rate-limit; cap sync age.
- Refresh-token rotation race lets two concurrent refreshes with a stolen token both succeed. `security/TokenService.java:102-114` → conditional `update … where revoked=false`.
- Z39.50 target lets staff make the server connect to internal hosts (port scan). `transfer/CopyCatalogService.java:74,128` → allow-list hosts.
- Changing a user's role doesn't end their sessions. `user/UserController.java:102-121` → revoke tokens on role change.
- 500 MB upload limit on every endpoint (a patron photo upload can fill the disk). `application.yml:22-23`.
- Staff can set any external `photoUrl`. `patron/PatronService.java:145` → derive from the stored photo only.
- Audit trail has no client IP and no record of staff viewing patron data. `common/AuditService.java:28`.
- Any patron can disconnect a kiosk (hold the corner 5 s, OK). `frontend/src/components/KioskSetup.tsx:122-127` → require a staff password.

**Correctness**
- Booking availability undercounts copies on loan, so a title can be over-booked. `CirculationService.java:680-686`.
- Two returns of different copies at once both pick the same waiting hold → one return fails (500). `CirculationService.java:439-460` → lock the queue head.
- Several status changes don't lock the copy (cancel hold, expire holds, mark lost, edit copy, stocktake "mark missing") and can overwrite a concurrent checkout. → lock, or add `@Version` to Item, HoldRequest, Loan, Fine.
- Reservation "position in line" is counted differently from the real queue (bookings go first). `CirculationService.java:605-608` vs `HoldRequestRepository.java:45-49`.
- Overdue fines are recognised by the words "Overdue…" in their description. `circulation/FineRepository.java:32` → a `kind` column.
- RFID bridge: the 15 s command timeout never applies (waits for output first) and a slow gate alarm stops all gate checks. `RfidBridge.java:276-277, 381`.

**Performance (grows with the data)**
- Report exports and previews load whole tables then filter in Java, with one extra query per row (N+1); every preview costs a full export. `report/ReportController.java:170-307`, `common/ReportTable.java` → filter and sort in SQL; preview with LIMIT.
- Excel/PDF/MARC exports are built fully in memory; MARC runs one query per title. `common/Xlsx.java`, `common/Pdf.java`, `transfer/CatalogTransferService.java:284` → streaming workbook, streamed responses, batched loading.
- Gate checks run one query per tag and re-read settings from the database on every call. `gate/GateController.java:163-186`, `common/SettingsService.java` → one `in (…)` query; cache settings.
- Notices are sent (SMTP/SMS) inside one long database transaction. `notice/NoticeService.java:92-110` → send outside the transaction, save each result separately.
- Attendance auto time-out scans the whole log every 10 minutes. `attendance/AttendanceAutoTimeout.java` (index in D1 fixes most of it).
- Renaming an author/subject heading loads the whole catalog. `authority/AuthorityService.java:98`.

**Tests**
- The integration tests silently *skip* when no test database is running, so only unit tests really guard the build. `src/test/.../it/IntegrationTest.java:36-43` → Testcontainers.
- No tests for: returns blocked by unpaid fines (pay/waive in one go), payments and receipts (incl. double payment), hold queue order and expiry, renewal limits, checkout limits with override, patron/kiosk access to other patrons' data, privacy retention (deletes data nightly), stocktake close, offline timestamps, notice retries.

---

## P3 — nice to have (maintainability)

- `CirculationService` is 835 lines with 13 dependencies (loans, returns, holds, bookings, fines, payments, receipts, schedulers); renewal logic is split with `RenewalService`. Split into Loan / Hold / Fine services.
- Business logic and transactions inside controllers (stocktake close, gate decisions, reports, labels) — move into services so they can be tested and reused by SIP2.
- Duplicated helpers: date-time formatting (5 copies), "MMM d, yyyy" with and without a fixed locale, PDF table code and colours (3 copies), `money()`, `blankToNull`. → one formatting utility and one PDF helper.
- Fully-qualified class names inline instead of imports (ReportController 12, CopyCatalogService 7, …); static mutable fields in `Pdf`.
- Magic numbers and setting defaults scattered in code (60 days, 400 days, "07:00", send hour 7).
- Comments: mostly explain *why* (good); trim the ones that repeat the method name or narrate history.
- Kiosk privacy polish: full name and card number on the self-check Done screen; typed ID and course on the attendance card. → first name, masked ID.
- Self-check Done screen lists returns and "Already scanned" lines as borrowed; Renew can be pressed twice.
- `/my?reserve=<id>` places a hold without asking the patron to confirm.
- Kiosk accessibility: no `aria-live` on result messages; focused input inside an `aria-hidden` form.
- Stocktake "mark missing" can mark a copy returned after its aisle was counted.
- Money inputs not limited to 2 decimals / column size (`@Digits`).
- One bad row aborts the whole patron CSV import.
- Imported copies get the server's date instead of Manila's (`LocalDate.now()` in `transfer/`).
- Authority variants and merged subjects cut mid-word at 1,000 characters (`authority/Authority.java`, `AuthorityService.java:107`).
- Blob URLs: `openPdf` never revokes; `download` revokes immediately (can cancel the download).

## Checked and fine

All SQL is parameterised; CSV exports neutralise formulas; XML parsing blocks XXE; file storage blocks path
traversal and checks file contents; every `/api/me/**` call takes the patron from the token and checks ownership;
passwords and PINs are BCrypt; refresh tokens are hashed and rotated with reuse detection (apart from the race
above); PIN reset is well designed; print slips escape HTML; `?next=` redirects stay on site; `.env` and backups are
not in git; tsc and eslint are clean; Z39.50 runs without command injection.

## Suggested order of work

1. **Deployment settings (an hour):** S5, S6, S7, bind 6001 — `.env` and `docker-compose.yml` only.
2. **RFID bridge (half a day):** S1, S2, timeout and alarm thread.
3. **SIP2 (half a day):** S3, S4, C5.
4. **Loan/fine integrity (1–2 days, with tests):** C1–C7, plus the locking items in P2.
5. **Desk/kiosk (1 day):** F1–F5.
6. **Indexes (an hour):** D1.
7. P2 performance and remaining security items; then P3 refactoring.

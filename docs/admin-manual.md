# ZSCMST Smart Library: Administrator Manual

For the system administrator and the head librarian. Day-to-day desk work is covered in
[user-manual.md](user-manual.md). Installation and developer details are in the [README](../README.md).

## 1. Installation and updates

```bash
cp .env.example .env            # set JWT_SECRET (32+ random characters), DB_PASSWORD, ADMIN_PASSWORD
docker compose up -d --build    # db, api, web, backup
```

- Web app on port 8081, API on 8080, SIP2 on 6001, PostgreSQL on 5433.
- First sign-in: `admin` with `ADMIN_PASSWORD`. **Change it immediately** (Account).
- Update to a new version: copy the new source over the old, then `docker compose up -d --build`.
  Database changes are applied automatically (Flyway) on start-up. Take a backup first.
- Put the system behind HTTPS before real use (README → HTTPS).

## 2. Users and devices
**Administration → Users & devices**

| Role | Can do |
|------|--------|
| ADMIN | Everything, including users, privacy, and audit trail |
| LIBRARIAN | Circulation with overrides, waivers, catalog import, settings |
| STAFF | Circulation desk, catalog editing, patrons |
| KIOSK | OPAC kiosk and attendance kiosk PCs; SIP2 self-check stations |
| GATE | RFID security gate middleware (device token) |

- **Add user** for people, **Add device** for kiosks and gates.
- A password you set for a person (new account or reset) is **temporary**: they must choose their own
  at the next sign-in. Device passwords stay as set.
- **Sign-in protection**: lock an account after N failed attempts for M minutes; **Unlock** from the list.
- Disable accounts of staff who leave instead of deleting them, so the audit trail stays readable.
- Gate device tokens are long-lived. To stop one (gate PC replaced or stolen), open the device and press
  **Revoke all tokens**, or disable the device; then issue a new token. Tokens issued before this
  feature existed can't be revoked this way: issue every gate a new token once.

## 3. Loan rules
**Administration → Loan rules & courses**

- **Library-wide rules**
  - Per material type: **room use only**, and a **loan period** that replaces the category's (e.g. Audiovisual 3 days).
  - How far ahead patrons can **book** (default 60 days).
  - How many days before pickup staff are warned that a booking is **at risk** (default 2).
  - The **default lost-item charge** suggested when a loan is marked lost.
- **Per patron category**
  - Loan days, item limit and renewals.
  - **Max reservations and bookings** (0 = no limit).
  - The **pickup window** for reserved copies.
  - Fine per open day, a **grace period** (late open days not charged), and a **maximum fine per item**.
  - The unpaid-fines limit that blocks borrowing.
- Programs/courses used on patron records and in statistics.

## 3a. System settings
**Administration → System settings** (ADMIN)

| Section | What it controls |
|---------|------------------|
| Library profile | System name, library and school name, short name, address, phone, e-mail, website, receipt header and footer. Used on every screen, receipts, PDF reports, e-mails, kiosks and SIP2 stations |
| Kiosks | OPAC kiosk reset time (default 90 s); attendance kiosk repeat-tap window (default 15 s); self-checkout: require PIN (default on), allow returns (default on) |
| Sign-in sessions | Access token minutes (15), "stay signed in" days (7), gate device token days (365). Once saved here, these replace the `JWT_*` environment values |
| Upload limits | E-books (100 MB), announcement recordings (10 MB), patron photos (5 MB); never more than 500 MB |

## 4. Library calendar
**Administration → Library calendar**: opening hours per weekday and closed days (holidays, breaks).
Due dates and pickup deadlines move to the next open day, fines count open days only, closed days
can't be booked, and visitors still timed in at closing are timed out automatically.

## 5. Catalog settings
- **Catalog fields**: extra fields shown on every record (e.g. *Thesis adviser*, *Accession number*),
  optionally visible in the OPAC.
- **Authorities**: preferred author and subject headings with variants. Renaming a heading updates
  every record; searching a variant finds the preferred heading.
- **Import/export**: Catalog → Import (CSV template or MARC21/MARCXML, with preview) and Export.

## 6. Notifications
**Administration → Notifications**: SMTP mailbox and SMS gateway, the daily send time, how many days
before the due date to remind, message templates, **Send test**, and the delivery log with failures.

## 7. Security gate and announcements
**Security gate**: alarm on/off and **alarm schedule** (always, opening hours only, or custom hours),
emergency mode, announcement presets with text, uploaded or recorded audio, and the voice used for
text-to-speech on kiosks.

## 8. Data privacy (RA 10173)
**Administration → Data privacy** (ADMIN only)

- **Privacy notice**: what is collected, why, who sees it, retention and rights. Shown at `/privacy`
  and linked from the home page, OPAC and patron sign-in. Fill in the Data Protection Officer and contact.
- **Retention limits** (0 = keep forever), applied nightly at 2:30 AM:

  | Setting | Effect |
  |---------|--------|
  | Loan, fine & reservation history | Settled history older than N years is detached from the patron (statistics keep their counts) |
  | Library visits | Visits older than N years are anonymized |
  | Expired patrons | Patrons whose card expired N years ago, with nothing on loan and no unpaid fines, are anonymized |
  | Security gate log | Gate events older than N days are deleted |
  | Sent notices | Copies of sent notices older than N days are deleted |
  | Audit trail | Entries older than N years are deleted |

  Use **Preview** to see how many records would change before saving, and **Apply now** to run it at once.
- **Patron requests** (patron page → *Data privacy…*):
  - *Download patron's data*: a JSON file with profile, loans, fines, reservations, visits, notices and e-book use.
  - *Anonymize / erase*: removes name, card, RFID, contact details, photo and PIN; cancels open
    reservations. Items must be returned and fines settled first. Cannot be undone; recorded in the audit trail.
- Patrons can download their own data in **My Library → Account**.
- Recommended policy to adopt (school decision): loans 5 years, visits 3 years, expired patrons 2 years,
  gate log 365 days, notices 180 days, audit 5 years.

## 9. Backups and restore
The `backup` service saves the database and uploaded files daily into `backups/` and keeps 14 days.
**Copy `backups/` to another drive or PC every week**, and test a restore each term:

```bash
docker compose exec backup /bin/sh /backup.sh once
scripts/restore.sh backups/db-<stamp>.dump backups/files-<stamp>.tar.gz
```

## 10. SIP2 self-check and RFID stations
The API listens for SIP2 on TCP port **6001** (to change it, add `APP_SIP2_PORT` to the `api` service's environment and the port mapping in `docker-compose.yml`; `APP_SIP2_ENABLED=false` turns it off).

1. Create a device account with role **KIOSK** for each station (Users & devices → Add device, type *Attendance kiosk*, with a password).
2. In the station's SIP2 settings: host = library server, port 6001, login user/password = that account,
   institution ID `ZSCMST`, checksums on or off (both work), no TLS.
3. Supported messages: login 93, status 99, patron status 23, patron information 63, item information 17,
   checkout 11, checkin 09, renew 29, end session 35, resend 97.
4. The same loan rules apply as at the desk. A station checkout with *no block = Y* (done while offline) is
   recorded at its original time with rules logged as overrides. Patron PINs are checked when the station sends one.
5. Every station action appears in the audit trail as `sip2:<account>`.

SIP2 is not encrypted: keep port 6001 reachable only from the library LAN (firewall), never from the internet.
Writing RFID tags (AFI/EAS security bit) is done by the tagging station's own vendor software.

Optional settings (database table `app_setting`): `sip2.institutionId`, `sip2.screenMessage`.

## 11. Exports
Reports, attendance, gate log, overdue, fines and stocktake exports are available as **Excel (CSV)** or
**PDF** (A4 landscape with the library header).

## 12. Audit trail
**Administration → Audit trail** (ADMIN) records every sign-in, checkout, check-in, override, waiver,
settings change, privacy action, SIP2 station action and offline upload, with the user and time.

- Filter by text in the details, user, action and date range.
- Export the filtered list as Excel (CSV) or PDF (up to 10,000 rows).

## 13. Kiosks
See [kiosk-lockdown.md](kiosk-lockdown.md) for setting up OPAC, attendance and self-checkout kiosk PCs.

## 14. Patron PIN resets
Patrons reset a forgotten PIN at `/my/forgot-pin` with a 6-digit code e-mailed to the address on their
record (valid 15 minutes, 5 tries, at most 3 codes per hour). This needs e-mail set up under
Notifications; without it, staff set a new PIN on the patron's page.

## 15. Automated tests
`scripts/mvn.sh test` runs unit tests and integration tests (loan rules, fines, holds, security) against
the `smartlibrary_test` database; GitHub Actions runs them on every push. See the README.

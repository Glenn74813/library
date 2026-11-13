# Bid compliance and gap analysis: ZSCMST Smart Library

**Procurement:** Procurement of Smart Library, Bid No. B-220-2026 (ABC ₱3,000,000)
**Checked against:** `bid docs.pdf` (Bid Data Sheet and Section VII Technical Specifications) and
Bid Bulletin No. 131-2026 (`sup.pdf`, Nov 10, 2026), which amends the OPAC kiosk, RFID gate and
book tag specifications and restates the Terms and Conditions.
**System checked:** ZSCMST Smart Library at commit `edb945a` and later (this repository).
**Date:** November 13, 2026

## Summary

| | Count |
|---|---|
| Software requirements fully met | 61 |
| Partly met | 0 (the RFID bridge is set up for the delivered reader, gate and tagging station on site) |
| Not met | 0 |
| Hardware or supplier items (not software; must be supplied and certified) | 10 |

The software meets every functional requirement of the Library Management System, mobile
application, OPAC kiosk, gate software and attendance kiosk. Six gaps were closed on November 13, 2026
(native Z39.50, report filters, Excel exports, tagging report, migration validation report, restore
test). The gate and tagging-station gaps were closed with the **RFID bridge**, which connects any reader or gate
(serial, TCP/IP, vendor command-line tool or push) and switches tag security through the vendor's tool. What
remains is configuring it for the delivered hardware at installation.

**Legend:** ✅ Compliant · ⚠️ Partly compliant (see gap) · 🔧 Hardware / supplier item ·
📄 Deliverable to prepare

---

## Item 1. Library Management System (1 set)

### Software

| # | Requirement | Status | Evidence in the system |
|---|---|---|---|
| 1.1 | Access to over 20 million free USMARC records from the Library of Congress and other Z39.50 servers | ✅ | **Copy-catalog** searches the Library of Congress over native **Z39.50** (YAZ client, `lx2.loc.gov:210/LCDB`) or SRU, the National Library of the Philippines, and **any other Z39.50 server** (host:port/database), importing full USMARC / MARC21 records (MARC-8 or UTF-8). *Closed G1, Nov 13, 2026.* |
| 1.2 | ISBN lookup | ✅ | **Lookup** (Open Library) on the record form; checks the number is a valid ISBN-10/13 first. |
| 1.3 | Authority control | ✅ | **Collection → Authorities**: preferred and variant names and subjects; suggestions while typing; searches follow variants. |
| 1.4 | Custom fields | ✅ | **Administration → Catalog fields**: text, number, date or choice fields, optionally shown in the OPAC. |
| 1.5 | Multi-format: books, AV, serials, theses | ✅ | Material types Book, Audiovisual, Serial, Thesis, E-book, Reference, Other. |
| 1.6 | Check-in/out and renewals | ✅ | Circulation desk (scanner, RFID, camera), patron page, self-checkout kiosk, My Library renewals with approval. |
| 1.7 | Holds / reservations | ✅ | Queue holds and date bookings, hold shelf, pickup windows, release slips. |
| 1.8 | Fines with payment receipts | ✅ | Overdue fines (open days only, grace days, cap per item), manual charges linked to a loan, payment receipts, end-of-day report, Cashier role. Returns wait until the borrowing's fines are cleared. |
| 1.9 | Loan rules per patron category | ✅ | Loan days, maximum loans, renewals, fine per day, grace days and fine limit per category, plus room-use-only per material type or copy. |
| 1.10 | Offline mode | ✅ | The desk queues check-outs and returns when the server can't be reached and uploads them later (each applied once); the attendance kiosk queues taps. |
| 1.11 | Patron: barcode/RFID ID, photo | ✅ | ID number (generated if left blank) and RFID UID; photo by upload or camera; printable CR80 library ID. |
| 1.12 | Patron: bulk import | ✅ | **Patrons → Import** (CSV). |
| 1.13 | Patron: expiry dates, block / clear | ✅ | Expiry date with warnings; block with reason; clearance certificate. |
| 1.14 | Automated notices: overdue, hold-ready, due-soon | ✅ | Scheduled notices of each type, plus renewal decisions. |
| 1.15 | Email / SMS delivery, editable templates | ✅ | Email (SMTP) and SMS (gateway URL and key) under **Administration → Notifications**, with editable templates. SMS needs an SMS gateway account. |
| 1.16 | OPAC search: keyword, title, author, subject, ISBN, call no. | ✅ | Field selector on the catalog and OPAC. |
| 1.17 | OPAC filters, relevance ranking | ✅ | Material-type filter; full-text search ranked by relevance (PostgreSQL `ts_rank`). |
| 1.18 | Reports: circulation, collection, overdue, fines, visitors, top borrowers | ✅ | **Reports** (circulation charts, top borrowers and titles), loans, overdue, fines, collection and patron exports; **Attendance** for visitors. |
| 1.19 | Reports: custom filters | ✅ | Period plus patron category, course, material type, section, copy status and fine status; they narrow the figures, charts and every export, and the export names the filters used. *Closed G2, Nov 13, 2026.* |
| 1.20 | Export to Excel / CSV / PDF | ✅ | Every export offers **Excel (.xlsx)**, CSV and PDF (Excel files have a title, bold header, frozen header row and filters). *Closed G3, Nov 13, 2026.* |
| 1.21 | Shelf inventory: scanner-based stocktake, missing / found reports | ✅ | **Stocktake** by section or aisle, printable shelf list, live ticks, per-aisle comparison, missing / misplaced / unexpected-status / unknown reports. |
| 1.22 | Unlimited users | ✅ | No user or seat limits. |

### Mobile application

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 1.23 | Android + iOS, **or responsive web app** | ✅ | Responsive web app (works on any phone browser); the spec accepts this option. |
| 1.24 | Search and availability | ✅ | My Library → Search & reserve; student catalog `/my/catalog`. |
| 1.25 | View loans / fines, renew items | ✅ | My Library: loans, fines, reservations, e-books, renewal requests, own library ID. |
| 1.26 | Librarian app: scan to check out / in via phone camera | ✅ | Camera scan buttons on the circulation desk. |
| 1.27 | Librarian app: patron lookup | ✅ | Patron search and camera scan of the library ID. |
| 1.28 | Librarian app: quick inventory via phone camera | ✅ | Camera scan in Stocktake. |

### Hardware (Automation Peripheral Package)

| # | Requirement | Status | Note |
|---|---|---|---|
| 1.29 | 2 wireless barcode scanners (1D + 2D/QR, USB, plug-and-play) | 🔧 | Supply. The system works with any keyboard-wedge scanner. |
| 1.30 | 1 receipt printer (80 mm thermal, auto-cutter, ≥ 200 mm/s, USB or LAN) | 🔧 | Supply. Slips are already laid out for 80 mm rolls and cut to length. |

## Item 2. OPAC interactive display kiosks (2 units)

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 2.1 | Hardware: 8 GB RAM, 128 GB, ≥ 21" multi-touch, Wi-Fi, Bluetooth, speaker, Gigabit Ethernet, 120×20×20 cm, 12 V 2 A, ≤ 30 W, Windows 11 installed | 🔧 | Supply. |
| 2.2 | Live link to LMS catalog (not a copy) | ✅ | `/opac` reads the live catalog. |
| 2.3 | Real-time availability | ✅ | Copies available / on loan / on hold, with shelf address. |
| 2.4 | On-screen keyboard | ✅ | Built-in on-screen keyboard. |
| 2.5 | Large buttons | ✅ | Kiosk layout with touch-sized controls. |
| 2.6 | Inactivity auto-reset | ✅ | Clears after the set idle time (default 90 s, adjustable). |
| 2.7 | Locked shell / browser; no OS, desktop or other-site access; auto-start after reboot | ✅ 📄 | Done at installation with Windows 11 Assigned Access / Edge kiosk mode, as in `docs/kiosk-lockdown.md`. Check during UAT. |

## Item 3. RFID overhead gate (1 unit)

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 3.1 | LMS link: real-time borrowed / unborrowed status via SIP2 or API | ✅ 📄 | Gate API (`/api/gate/device/check`, device token) and SIP2. The **RFID bridge** (`rfid-bridge/`, gate mode) connects any gate (serial, TCP/IP, vendor tool or push), checks each tag with the library, runs the alarm command, and reports heartbeats, visitors, tamper and power events. Tested end to end Nov 13, 2026. *Closed G5; configure for the delivered gate on site.* |
| 3.2 | Dashboard: alarm events with item ID / title, visitor count, gate status and health, logs, export to Excel / PDF | ✅ | **Security gate** page: alarms with item, visitors, tamper and power counts, gates online / last seen, event log, CSV / PDF export. |
| 3.3 | Event log: timestamped alarm, tamper, power-loss events | ✅ | Event types ALARM, TAMPER, POWER_LOSS, POWER_RESTORED, VISITOR, EMERGENCY, HEARTBEAT, BROADCAST. |
| 3.4 | Configuration security (password-protected), adjustable schedule | ✅ | Settings for admins only; gate device keys; arming schedule Always / Open hours / Custom. |
| 3.5 | Downloadable data reports: tagging, circulation, alarm list | ✅ | **Security gate** page: Alarm / event log, Circulation report and **RFID Tagging report** (every copy in shelf order, tagged or not, with tagged / untagged counts), each as Excel, CSV or PDF. *Closed G4, Nov 13, 2026.* |
| 3.6 | Green software practice (paperless reports) | ✅ | Reports on screen and as files; receipts print only on demand. |
| 3.7 | Disaster-risk tool: audio alarm broadcast, visual broadcast on networked displays, customizable text-to-speech | ✅ | Announcements from the Security gate page, shown and spoken on every kiosk screen, with presets, recorded audio and voice settings. |
| 3.8 | Hardware: UHF 918–920 MHz, ISO 15693 and 18000-6C, reads ISO 28560 data; ceiling type; 2 m read range; Ethernet / USB / Wi-Fi; 110–220 V; 25 W; detects security bit / AFI and item ID; covers full entrance width | 🔧 | Supply. Note that ISO 15693 is an HF standard: the specs mix HF and UHF, so confirm with the BAC which one the gate must read. |
| 3.9 | NTC registration / permit / certification | 🔧 📄 | Supplier document for the gate. |

## Item 4. Circulation and tagging device (1 unit)

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 4.1 | UHF 918–920 MHz, ISO 18000-6C, USB 3.0, shielded design, 25 W adjustable, anti-collision | 🔧 | Supply. |
| 4.2 | Integration with the LMS circulation module | ✅ 📄 | Copies have an RFID EPC field; the **RFID station** (bridge) on the desk or kiosk reads tags from any reader (keyboard wedge, serial, TCP/IP, vendor tool, push). *Closed G6.* |
| 4.3 | 2-in-1: tagging (barcode → RFID data) and circulation, **including RFID security ON/OFF** | ✅ 📄 | Copy form **Read** / **Write** tag; checkout (desk, kiosk, hold release) switches security **off**, return switches it **on**, through the vendor's write tool configured in the bridge; staff are warned if a tag could not be switched. Tested with the simulator and command-line writer Nov 13, 2026. *Closed G6; configure the vendor's tool on site.* |

## Item 5. UHF RFID book tags (20,000 pcs)

| # | Requirement | Status | Note |
|---|---|---|---|
| 5.1 | 918–920 MHz, ISO 18000-6C EPC Class 1 Gen 2, EAS / AFI / DSFID, passive, Impinj M780, 100,000 cycles, 10-year retention, 5 m range | 🔧 | Supply. |

## Item 6. Library attendance kiosk (1 unit)

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 6.1 | a.1 Stand-by mode: information board with video playback and editable messages | ✅ | Stand-by mode with video URL and rotating messages. |
| 6.2 | a.2 Attendance mode: records time-in; attendance statistics per patron | ✅ | Time in / out; patron page **Attendance** tab with visits and time spent. |
| 6.3 | b.1 Single-tap (time-in only) mode | ✅ | **Single tap** setting. |
| 6.4 | b.2 Dashboard report accessible via local network | ✅ | Web-based **Attendance** page. |
| 6.5 | b.3 Graphs: top 5 programs / courses, top 5 patron categories | ✅ | Attendance statistics charts. |
| 6.6 | b.4 Add / edit courses and patron categories | ✅ | **Administration → Loan rules & courses**. |
| 6.7 | b.5 Manual patron registration | ✅ | New patron form (ID number generated if blank). |
| 6.8 | b.6 Upload patron list | ✅ | Patron CSV import. |
| 6.9 | b.7 Editable entry / exit messages | ✅ | Kiosk settings. |
| 6.10 | b.8 Download attendance statistics | ✅ | CSV / PDF export. |
| 6.11 | b.9 ID entry by RFID card, barcode, virtual keyboard | ✅ | All three. |
| 6.12 | b.10 Works with or without internet | ✅ | Runs on the local network; taps are queued if the server can't be reached. |
| 6.13 | Hardware: 27" touch screen, 1920×1080, speaker, dual HF RFID reader, 1D/2D scanner, USB / LAN / Wi-Fi, floor-standing | 🔧 | Supply. |

## Terms and Conditions (Bid Bulletin No. 131-2026)

| # | Term | Status | Evidence / action |
|---|---|---|---|
| T1 | Ownership & licensing: full source code, database and documentation turned over; perpetual license, no recurring or per-user fees | ✅ 📄 | Custom-built; the repository, database and documents can be handed over in full. Prepare the turnover package. |
| T2 | Interoperability: integrates with all delivered devices; supports MARC21, Z39.50, SIP2 / NCIP | ✅ 📄 | MARC21 import / export ✅; native Z39.50 ✅; SIP2 server ✅ (NCIP not needed, since the spec reads "SIP2/NCIP"); any reader, pad or gate through the RFID bridge ✅ (set up for the delivered models at installation). |
| T3 | Data migration of all existing records, Data Privacy Act compliant, validation report signed by both parties | ✅ 📄 | Catalog import (MARC21, CSV) with preview and problem report; patron CSV import; every import recorded. **Reports → Data migration validation report** (PDF / Excel): imports, rejected rows and reasons, totals now in the system, Data Privacy Act handling, sign-off lines for the supplier and the LRC. To be printed and signed after the real migration. *Closed G7, Nov 13, 2026.* |
| T4 | Security & backup: role-based access, encrypted passwords, audit trail, automated daily backups with a tested restore procedure | ✅ | Roles Admin, Librarian, Staff, Cashier, Kiosk, Gate; bcrypt passwords; audit trail; daily database and file backups (`./backups`); `scripts/restore.sh`. Restore tested Nov 13, 2026 with `scripts/restore-test.sh`: **passed**, all 31 tables and files match (`docs/restore-test-record.md`, to be signed). *Closed G8.* |
| T5 | Installation & acceptance: on-site install, configure, test; UAT witnessed by the LRC and the Inspection Committee | ✅ 📄 | `docs/uat-checklist.md` covers every module. Production must run on a school server on campus, not on the developer's PC. |
| T6 | Training & documentation: hands-on training; user and administrator manuals (printed and digital) | ✅ 📄 | `docs/user-manual.md`, `docs/admin-manual.md`, `docs/kiosk-lockdown.md`. Printed copies and a training plan still have to be prepared. |
| T7 | Warranty & support: 1 year on hardware and software (parts, labor, on-site), free updates, 24 h response, 72 h resolution | 🔧 📄 | Supplier commitment. |
| T8 | Delivery & payment | 🔧 | Contract terms. |

---

## Gaps and how to close them

| Gap | Requirement | What is missing | How to close it | Effort | Priority |
|---|---|---|---|---|---|
| ~~G1~~ | 1.1, T2: Z39.50 | **Closed Nov 13, 2026**: native Z39.50 (YAZ) for the Library of Congress and any other server. | | | |
| ~~G2~~ | 1.19: custom report filters | **Closed Nov 13, 2026**: category, course, material type, section, copy and fine status. | | | |
| ~~G3~~ | 1.20: Excel export | **Closed Nov 13, 2026**: native `.xlsx` on every export. | | | |
| ~~G4~~ | 3.5: tagging report | **Closed Nov 13, 2026**: RFID Tagging report on the Reports and Security gate pages. | | | |
| ~~G5~~ | 3.1, T2: gate link | **Closed Nov 13, 2026**: RFID bridge in gate mode, any gate; set up for the delivered gate on site. | | | |
| ~~G6~~ | 4.2–4.3, T2: tagging and security ON/OFF | **Closed Nov 13, 2026**: RFID station (bridge) with read, write and security on/off through the vendor's tool; set up on site. | | | |
| ~~G7~~ | T3: migration validation report | **Closed Nov 13, 2026**: Reports → Data migration validation report, with sign-off lines. | | | |
| ~~G8~~ | T4: tested restore procedure | **Closed Nov 13, 2026**: `scripts/restore-test.sh` passed; record in `docs/restore-test-record.md` (to be signed). | | | |

### Not a gap under the bid documents

- **Track record of 3 SUC installations.** This was term no. 4 in the Purchase Request (PR No.
  2409-007), but it is **not** in the bidding documents or Bid Bulletin No. 131-2026. If the BAC
  reinstates it, the system has no SUC installations yet and would not comply.
- **SLCC.** The bidder's completed similar contract (Smart Library Management System, at least 50% of
  the ABC, within 5 years) is a company qualification, not a system feature.

## Items to confirm with the BAC

1. **Gate RFID standard.** The gate spec lists UHF 918–920 MHz together with ISO 15693, which is an
   HF standard. The Purchase Request said HF 13.56 MHz.
2. **Whether the 3-SUC track record applies.** It is in the Purchase Request but not in the bid.
3. **Gate emergency / evacuation mode.** It is in the Purchase Request but not in the bulletin. The
   system already logs emergency on and off events.

## Remaining work (on site, with the delivered hardware)

1. For each reader, pad and the gate: set up the RFID bridge (`rfid-bridge/README.md`) for how it connects
   (serial, TCP/IP, vendor tool or push) and, for the tagging station, the vendor's write and EAS commands.
2. Migrate the library's real data; print and sign the **Data migration validation report**.
3. Run the full UAT checklist and sign the restore test record.

All software gaps (G1–G8) were closed on November 13, 2026.

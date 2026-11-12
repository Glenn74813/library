# ZSCMST Smart Library: User Acceptance Test (UAT) Checklist

Run on the production servers and devices before acceptance. For each test, write **P** (pass) or **F** (fail)
in the result column, the tester's initials, and notes. Use test patrons and items, and clean up afterwards.

Tester(s): ______________________  Date: ____________  Version / build: ____________

## A. Access and security

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| A1 | Open the system over HTTPS from a library PC and a phone | Padlock shown; no certificate warning | | |
| A2 | Sign in as admin, librarian, staff | Each sees only their allowed menus | | |
| A3 | Enter a wrong password repeatedly | Account locks after the configured attempts; admin can unlock | | |
| A4 | Leave a staff session idle past 15 minutes, then continue | Session refreshes silently, or asks to sign in again | | |
| A5 | Staff (not librarian) tries to override a loan limit | Override is not offered / refused | | |
| A6 | Check the audit trail after tests | Sign-ins, checkouts, overrides, settings changes listed with user and time | | |

## B. Cataloging

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| B1 | New record via ISBN **Lookup** | Title, authors, publisher filled | | |
| B2 | Copy-catalog via **LoC** (title search) | Record fields filled from the Library of Congress | | |
| B3 | Add 2 copies with barcode, RFID EPC, section, aisle, shelf | Copies listed with shelf location | | |
| B4 | Author field suggests existing headings; variant search finds preferred heading | Works | | |
| B5 | Add a custom catalog field and fill it | Shown on record (and OPAC if enabled) | | |
| B6 | Import the CSV template with 3 rows: preview then commit | Preview shows 3 new titles; committed records searchable | | |
| B7 | Import a MARC21 (.mrc) file | Records and holdings imported; duplicates skipped | | |
| B8 | Export catalog as MARC21, MARCXML, CSV, PDF | Files open correctly | | |
| B9 | Upload a school-owned PDF and EPUB; link a commercial e-book | Patron can read school-owned; commercial opens vendor link | | |

## C. Patrons

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| C1 | Register a patron by scanning the ID barcode / tapping the RFID card into the card field | Card number / UID captured | | |
| C2 | Take a photo with the webcam (HTTPS) | Photo shown on patron page and at the desk | | |
| C3 | Set portal PIN; patron signs in at `/my` | Patron sees loans, holds, fines | | |
| C4 | Block a patron; try to check out | Checkout refused with reason | | |

## D. Circulation

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| D1 | Check out with USB barcode scanner | Due date per category rules, skipping closed days | | |
| D2 | Check out with RFID desk pad | Same as D1 | | |
| D3 | Check out with phone/PC camera scanning | Same as D1 | | |
| D4 | Exceed loan limit; librarian overrides | Override recorded in audit trail | | |
| D5 | Check out a room-use-only item | Refused (override possible) | | |
| D6 | Renew at desk and in `/my` | New due date; refused when overdue or at limit | | |
| D7 | Return an overdue item | Fine = open days late × rate; shelf location shown | | |
| D8 | Pay fines; reprint the receipt | Official receipt number; fines marked paid | | |
| D9 | Waive a fine with reason (librarian) | Fine waived; audit entry | | |
| D10 | Mark an item lost with a charge | Replacement fine added; item LOST | | |

## E. Holds and bookings

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| E1 | Patron reserves a title from the OPAC copy row | Hold PENDING with queue position | | |
| E2 | Return a copy of that title | Check-in says *Place on hold shelf*; patron notified | | |
| E3 | Hold not picked up within pickup window | Hold EXPIRED; copy goes to next in queue or shelf | | |
| E4 | Book a title for a future date from the calendar | Only free dates selectable; booking SCHEDULED | | |
| E5 | Lend a booked title to someone else | Due date shortened before the booking; notice shown | | |
| E6 | Release a ready hold; check *closed by* | Released to patron; staff name recorded | | |

## F. Offline circulation desk

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| F1 | Unplug the server's network (or stop the API); check out 2 items, check in 1 | Desk shows *Offline mode*, 3 saved | | |
| F2 | Restore the network | Uploads within 30 s with original times; *SYNCED* lines in session log | | |
| F3 | Offline checkout of an unknown barcode, then restore | Listed under *needs attention*; dismissable | | |

## G. OPAC and kiosks

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| G1 | OPAC search by title/author/subject/ISBN | Results with live availability and location | | |
| G2 | OPAC kiosk mode: on-screen keyboard; idle 90 s | Resets to start | | |
| G3 | Attendance kiosk: tap RFID card / scan barcode / enter ID | Time-in shown; time-out on second tap (if enabled) | | |
| G4 | Attendance kiosk offline: tap 2 cards, reconnect | Taps synced with original times | | |
| G5 | Kiosk PCs locked down per kiosk-lockdown.md | Cannot exit browser, open other apps, or reach settings | | |
| G6 | Announcement from Security gate page (TTS voice and MP3) | Plays on kiosks | | |

## H. Security gate and RFID

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| H1 | Walk out with an unborrowed tagged item | Alarm; event logged; bell shows alarm | | |
| H2 | Walk out with a borrowed item | No alarm | | |
| H3 | Emergency mode on | Gate opens; no alarms; banner on staff pages | | |
| H4 | Alarm schedule *opening hours only*, test after hours | Behaves per schedule | | |
| H5 | Gate tamper / power loss event | Logged; shown in bell | | |
| H6 | SIP2 self-check station: login, checkout, checkin, renew, patron info | Works with the same rules as the desk; audit shows `sip2:<station>` | | |
| H7 | Self-check station checkout sets the security bit off; check-in sets it on | Gate behaves accordingly (vendor station) | | |

## I. Stocktake

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| I1 | Start session; scan a shelf with handheld/RFID | Scans counted | | |
| I2 | Report | Missing, misplaced, unexpected status, unknown codes correct | | |
| I3 | Close session marking missing | Unscanned items MISSING; found LOST items AVAILABLE | | |

## J. Notifications

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| J1 | Configure SMTP; **Send test** | E-mail received | | |
| J2 | Configure SMS gateway; **Send test** | SMS received | | |
| J3 | Due-soon, overdue and hold-ready notices | Sent at the daily time; logged in delivery log | | |

## K. Reports and exports

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| K1 | Dashboard and Reports charts for a period | Figures match the loans/visits made during UAT | | |
| K2 | Each export as Excel (CSV) and PDF | Opens in Excel / PDF reader with correct rows | | |
| K3 | Attendance and gate log exports by date range | Correct range | | |

## L. Data privacy (RA 10173)

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| L1 | `/privacy` shows the approved notice and DPO contact | Correct text | | |
| L2 | Download a patron's data (staff) and own data (`/my`) | JSON contains profile, loans, fines, visits | | |
| L3 | Anonymize a test patron with a loan out | Refused until returned | | |
| L4 | Anonymize after return | Name/card/contact/photo removed; history kept anonymously; audit entry | | |
| L5 | Set retention limits; **Preview** | Counts shown; nothing changed until applied | | |

## M. Administration, backup and recovery

| # | Test | Expected result | Result | Notes |
|---|------|-----------------|--------|-------|
| M1 | Library calendar: add a holiday | Due dates skip it; it can't be booked | | |
| M2 | Change loan rules for a category | New checkouts use them | | |
| M3 | Run a backup now | New files in `backups/` | | |
| M4 | Restore the backup on a test machine | System works with the restored data | | |
| M5 | Restart the server PC | All services return automatically | | |
| M6 | Mobile: staff and patron screens on a phone | Usable without horizontal scrolling | | |
| M7 | System settings: change the library name and receipt footer | New name on top bar, home, kiosks, receipt, PDF header, e-mail signature | | |
| M8 | Set a loan period for Audiovisual (e.g. 3 days); check out an AV item | Due in 3 days (next open day) | | |
| M9 | Set max reservations = 1 for Student; reserve two titles | Second reservation refused | | |
| M10 | Set grace 1 day and cap PHP 50; return an item 20 days late | Fine = (late open days − 1) × rate, at most PHP 50 | | |
| M11 | Revoke a gate device's tokens | Gate calls fail (401) until a new token is entered | | |
| M12 | Audit trail: filter by user, action and dates; export PDF | Only matching entries, also in the file | | |
| M13 | Self-checkout: card + PIN, borrow 2 items with the RFID pad, renew one, print slip | Due dates shown; desk sees the loans | | |
| M14 | Self-checkout: wrong PIN 5 times | Card locked for the set minutes; desk can still serve the patron | | |
| M15 | Self-checkout: item refused (limit, hold for someone else, room use) | Clear message to go to the desk | | |
| M16 | Patron "Forgot PIN" with e-mail on record | Code e-mailed; new PIN works; code can't be reused | | |
| M17 | Admin resets a staff password | Staff must choose a new password before anything else | | |
| M18 | Print barcode labels and spine labels on real sheets; print ID cards on the card printer (CR80) and on an A4 sheet | Print lines up with the labels; cards print edge to edge at 85.6 × 54 mm | | |
| M19 | Reports → E-book usage after patrons open e-books | Openings, readers and titles match | | |
| M20 | Patron asks to renew in My Library | Shows "Renewal requested"; appears under Renewal requests and in the bell | | |
| M21 | Staff reject a renewal without a reason, then with one | Refused without; patron gets the reason and keeps the due date | | |
| M22 | Staff approve a renewal | New due date; patron notified | | |
| M23 | Patron uploads a photo, and takes one with a phone camera | Photo on patron page and on the printed ID card | | |

## Sign-off

| Role | Name | Signature | Date |
|------|------|-----------|------|
| Head Librarian | | | |
| IT / System Administrator | | | |
| Supplier | | | |

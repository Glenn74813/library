# ZSCMST Smart Library: User Manual

For library staff (librarians and assistants) and patrons. The administrator's tasks (users, rules,
backups, privacy, devices) are in [admin-manual.md](admin-manual.md).

The system runs in a web browser on PCs, tablets and phones. The address is set by the
administrator, for example `https://library.zscmst.edu.ph`.

| Who | Where | Signs in with |
|-----|-------|---------------|
| Staff | `/staff` | Username and password |
| Patrons (students, faculty, staff) | `/my` | Library ID number and a PIN from the circulation desk |
| Anyone | `/opac` (kiosk view) or `/my/catalog` (on your own device) | Nothing; searching the catalog is public |

---

## 1. Staff: daily work

### 1.1 Signing in
Open `/login`, enter your username and password. After several wrong passwords the account is
locked for a while; ask an administrator to unlock it. Forgot your password? An administrator sets a
temporary one; at the next sign-in you must choose your own before doing anything else. Change your password under **Account**
(bottom of the side menu).

### 1.2 Dashboard
Shows loans today, overdue items, holds waiting for pickup, visitors, and charts. The **bell** at the
top lists tasks that need action: overdue items, holds on the shelf or expiring today, bookings at risk,
gate alarms, tamper events and offline gates.

### 1.3 Checking items out
**Circulation → Check out / in → Check out / renew**

1. Scan the patron's library ID (barcode or RFID), or type the ID number, and press Enter.
   Their photo, loans, fines and any **Attention** warnings appear.
2. Scan each item. The due date is shown in *This session* on the right.
3. If the system refuses (loan limit, unpaid fines, expired card, room-use-only), a librarian can tick
   **Override loan limits**. Overrides are recorded in the audit trail.
4. **Renew** an item from the *Current loans* list.

A **short loan** message means another patron booked the title; the due date was moved earlier.
Tell the patron.

No scanner? Click the **camera** button next to any scan box to use the PC's or phone's camera.

### 1.4 Checking items in
**Check in (return)** tab: scan each returned item. The large message tells you what to do:
*Return to Fiction Section · Aisle 3 · Shelf 2*, or *Place on hold shelf for (name)*.
**A book with fines is only taken back once they are cleared.** If the item is overdue, or has a charge
linked to that borrowing (e.g. damage), a window lists them with the total: **Receive and return** pays
them on one receipt and completes the return; **Waive…** (librarian or admin, with a reason) clears them;
**Cancel** leaves the book on loan. Overdue fines count only days the library was open. The patron's page
(Loans → **Return**) works the same way. Returns saved while the desk is offline are not held up.

### 1.5 When the network or server is down (offline desk)
If the server cannot be reached, the desk switches to **Offline mode** by itself:

- Check-outs: scan the card, then the items, and press **Save checkout**. The card can't be
  verified while offline.
- Check-ins: scan as usual.
- Transactions are saved **in that browser on that PC**. Keep the page open and don't clear the
  browser's data.
- When the server is back they upload automatically (or click **Upload now**) with their real times,
  so due dates and fines are correct. Anything that broke a loan rule is flagged; anything that
  could not be applied is listed under **Offline transactions that need attention**. Fix those by
  hand, then click **Dismiss all**.

### 1.5a Receipt printer slips
The 80 mm receipt printer prints these slips (only the slip prints, sized to its length):

| Slip | Where |
|---|---|
| Borrowing slip (items, due dates, QR to My Library) | Circulation desk → **Print receipt** next to the patron |
| Return receipt (items returned, time, any fine) | Check in → **Print receipt** (one per patron) |
| Hold shelf slip (card no. and initials, pickup-by date) | Check in → **Print hold slip** when a returned copy is set aside for a reservation; put it in the book |
| PIN slip | When staff set or reset a patron's PIN (the library ID is the only card; print it from **Print… → Library ID**) |
| Library clearance (reference no. + QR) | Patron page → **Print… → Library clearance…** (only with nothing on loan and no unpaid fines). Check one with **Patrons → Verify clearance** |
| Payment receipt (marked "Not an official receipt") | When fines are paid, or **Receipt** on the Fines page |
| End-of-day collection report | **Fines & payments → End-of-day report** |

Nothing prints by itself: staff check the details on screen, then press the print button. Releasing a hold shows the loan with a **Print receipt** button.

**Print log.** Every slip, payment receipt, clearance and library ID that is printed is recorded with who printed it and when.
- Each patron's page has a **Printed** tab (also **Print… → Print history**) listing everything printed for them.
- **Security & reports → Print log** lists all prints, filterable by document, date and **Reprints only**.
- Printing a library ID that was printed before asks first, showing when and by whom. A clearance shows the clearances already issued.
- A second or later copy of a receipt or clearance prints **REPRINT · COPY n** at the top, and the log shows the copy number.
- PIN slips are logged without the PIN.
Start the desk browser with `--kiosk-printing` (receipt printer as default) to skip the print dialog.

### 1.6 Holds, reservations and bookings
**Circulation → Holds**

- *Reserve* puts the patron in the queue for the next free copy. When a copy comes back, check-in
  routes it to the hold shelf and the patron is notified. The copy waits for the pickup window set
  for the patron's category.
- *Book a date* reserves a copy for a chosen pickup date. The calendar shows only the dates when a copy
  is free.
- **Release** a ready hold to lend it to the patron (the release is recorded with your name), or
  **Cancel** it.
- From a patron's page: **Reserve / book**. From the OPAC: the **Reserve** and **Book** buttons next to
  each copy.

### 1.6a Renewal requests
**Circulation → Renewal requests**. When patrons renew in My Library, at the self-checkout kiosk or
a self-check station, the renewal waits here (the bell shows how many). Each row shows the current
due date and the due date **if approved now**, already adjusted for other patrons' reservations and
bookings; a red note means it can't be renewed any more (e.g. someone reserved the title since).
- **Approve** renews the loan and tells the patron the new due date.
- **Reject** needs a reason (pick one or type your own); the patron keeps the original due date and
  receives the reason by e-mail/SMS and in My Library.
- Renewals you do at the desk are immediate. A request is cancelled automatically if the item comes back.
- A request made before the due date can still be approved after it.

### 1.7 Fines and payments
**Circulation → Fines & payments**: select unpaid fines and **Pay** to print a payment receipt (the library issues the official receipt)
(reprint any time), or **Waive** with a reason (librarian). **Add charge** on the patron's Fines tab opens
a form: choose the borrowing it is for (a book on loan now or one already returned) or a general charge,
the reason (damaged book, water damage, missing barcode, replacement library ID, other) and the amount. A
charge for a book still on loan must be cleared before that book can be returned.

### 1.7a Labels and library IDs
**Collection → Labels & library IDs**
- **Item labels**: barcode labels (Avery L7160, 21 per sheet) or spine labels (Avery L7651, 65 per sheet)
  showing the copy's shelf address from Sections & Aisles (aisle and shelf in a dark band, section under it)
  above the call number, so books can be shelved without a catalog search.
  Scan the barcodes, or pick "copies added since" for a batch of new books.
  *Skip labels* reuses a partly used sheet. A record's copy list also has **Print labels…**.
- **Library IDs**: bank-card size, 10 per A4 sheet, with photo, ID number and barcode. Print
  by ID numbers, category, program or registration date; or **Library ID** on a patron's page.
- Print at **100% / Actual size**, not "Fit to page".

### 1.8 Catalog
**Collection → Catalog**

- **New record**: type the ISBN and press **Lookup** (Open Library), or **Copy-catalog** to search the
  **Library of Congress** (foreign books, LC call numbers) or the **National Library of the Philippines**
  (Philippine books, Dewey call numbers) by ISBN, title or author. The window remembers the last source
  used. Check the copied data, then save.
- Authors and subjects suggest existing headings as you type, which keeps the catalog consistent.
  Variant names are managed under **Authorities**.
- Add **copies** (barcode, RFID EPC, section, aisle, shelf, price). Leave **Barcode** empty and the
  system assigns the next library barcode (LRC-000003, LRC-000004, ...) when you save; then print its
  barcode label and stick it on the book. Scan a pre-printed library label instead if you have one.
  The barcode is the library's own number for that copy, not the ISBN. Mark room-use-only
  material types under Administration.
- **E-book / digital copy** panel: upload a school-owned PDF/EPUB, or link a commercial e-book.
- **Import** a CSV or MARC file. A preview runs first; nothing is saved until you confirm.
  **Export** the catalog as Excel (CSV), PDF, MARC21 or MARCXML.

### 1.9 Patrons
**People → Patrons → New patron**: ID number (scan the ID's barcode or tap the RFID card into the
field), name, category, course, contact details, expiry. On the patron's page:

- **Photo**: upload or take one with the webcam.
- **Set portal PIN** so the patron can use `/my`.
- Tabs for loans, history, holds, fines and attendance.
- **Data privacy…** → *Download patron's data* when a patron asks for a copy (see section 3).

### 1.10 Attendance
The attendance kiosk records visits. **People → Attendance** shows who is inside, the log (searchable),
statistics, and **Download report…** as Excel or PDF.

### 1.11 Security gate
**Security & reports → Security gate**: live gate events and alarms, **emergency mode** (opens the gates
and stops the alarm), alarm schedule, and **announcements**: pick a preset or type a message,
choose the voice or play a recorded MP3, and send it to the kiosks.

### 1.11a Sections & Aisles (administrators)
**Administration → Sections & Aisles** is the library's layout: sections (Fiction Section, Filipiniana,
...), the aisles in each section and how many shelves each aisle has. Only administrators change it.
Click a section to rename it (its copies move with it), merge it into another by using that section's
name, or add aisles: how many, and how many shelves each (aisles are numbered after the library's
highest aisle, or give one a name such as 3A). Click an aisle to rename it or change its shelves.
Sections and aisles can be removed once no copies are in them.
A copy's **Section / area**, **Aisle** and **Shelf** are picked from this layout, so a shelf beyond an
aisle's last shelf can't be chosen. Imported copies with a new section or aisle add it to the layout.

### 1.12 Stocktake
**Collection → Stocktake**: start a session for a section, or one aisle of it, from the layout above.
The **Shelf list** tab lists every copy that should be on those shelves in shelf order (print it with
**Print shelf list…**) and ticks each one as it is scanned (scanner, RFID pad or camera), with found and
missing counts per aisle. The report compares the shelf list with what was scanned, aisle by aisle, and
lists *missing*, *misplaced* (another section or aisle), *unexpected status* and *unknown codes*.
Closing the session can mark unscanned items MISSING; scanned LOST/MISSING items become AVAILABLE.
**Download report…** as Excel or PDF.

### 1.12a Self-checkout kiosk
Patrons borrow on their own at `/kiosk/selfcheck`: tap or scan the card, enter the PIN, then place
each item on the RFID pad or scan it. The same loan rules apply as at the desk, but nothing can be
overridden: anything refused is sent to the desk. Patrons can also renew there, and return items if
that is allowed: the kiosk checks the item in, and the patron leaves it on the return cart. An item with
fines (overdue, damage...) can't be returned at the kiosk: the patron is told to see a staff member at the desk. Staff then
shelve each one, or put it on the hold shelf if a reservation is waiting (the Holds page shows which).
A printable slip lists the due dates.

### 1.13 Reports
**Security & reports → Reports**: choose the period, view circulation and visitor charts, top
borrowers and titles, and export loans, overdue, fines, collection, patrons, attendance and gate
events. Use the format picker to choose **Excel (CSV)** or **PDF**.
The **E-book usage** section shows how often school-owned e-books were opened, by how many patrons,
from which programs, and the most-read titles.

---

## 2. Patrons

### 2.1 Searching the catalog (OPAC)
Use a library kiosk (`/opac`, with an on-screen keyboard), or the **Catalog** link in My Library, which opens the normal web view on your own device. Search by title, author, subject or ISBN.

At a catalog kiosk you can also borrow or reserve: open a title, tap **Borrow or reserve** (or **Reserve or book** when
all copies are out), then scan your library ID or type your ID number (and your PIN, if the library asks for it).
- **Borrow now** — a copy on the shelf is set aside under your name at the circulation desk; collect it there.
- **Reserve** — when all copies are out, you join the line and are notified when one is waiting for you.
- **Book it for a later date** — pick the day you want to collect it.
Nothing stays signed in on the kiosk. Whether a PIN is needed is set under **System settings → Kiosks**. Each copy shows
whether it is **Available** or **On loan** and where it is shelved. School-owned e-books open in the reader.

### 2.2 My Library (`/my`)
Sign in with your ID number and PIN (get the PIN at the desk). You can:

- see your loans and due dates, and ask to **renew** (unless overdue or at the renewal limit). The
  library approves or rejects the request; you are told by e-mail/SMS and under *Renewal requests*.
  Keep the item until then;
- add or change your **library ID photo**: upload a picture or take one with your phone or PC camera
  (Account tab);
- **reserve** a title, or **book** it for a pickup date from the availability calendar;
- see fines and receipts;
- read school-owned e-books;
- change your PIN (forgot it? **Forgot PIN?** on the sign-in page e-mails you a code, if the library
  has your e-mail address);
- **Download my data**: a copy of everything the library keeps about you.

### 2.3 Attendance kiosk
Tap your library ID on the reader when you enter (and leave, if the library uses time-out). No card?
Press **No card? Enter ID**.

---

## 3. Your privacy

The library's privacy notice is at `/privacy` and linked on the sign-in, OPAC and home pages.
To see, correct, download or erase your data, contact the Data Protection Officer named in the notice,
or download your own copy in **My Library → Account**.

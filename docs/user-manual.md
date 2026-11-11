# ZSCMST Smart Library: User Manual

For library staff (librarians and assistants) and patrons. The administrator's tasks (users, rules,
backups, privacy, devices) are in [admin-manual.md](admin-manual.md).

The system runs in a web browser on PCs, tablets and phones. The address is set by the
administrator, for example `https://library.zscmst.edu.ph`.

| Who | Where | Signs in with |
|-----|-------|---------------|
| Staff | `/staff` | Username and password |
| Patrons (students, faculty, staff) | `/my` | Library card / ID number and a PIN from the circulation desk |
| Anyone | `/opac` | Nothing; searching the catalog is public |

---

## 1. Staff: daily work

### 1.1 Signing in
Open `/login`, enter your username and password. After several wrong passwords the account is
locked for a while; ask an administrator to unlock it. Change your password under **Account**
(bottom of the side menu).

### 1.2 Dashboard
Shows loans today, overdue items, holds waiting for pickup, visitors, and charts. The **bell** at the
top lists tasks that need action: overdue items, holds on the shelf or expiring today, bookings at risk,
gate alarms, tamper events and offline gates.

### 1.3 Checking items out
**Circulation → Check out / in → Check out / renew**

1. Scan the patron's card (barcode or RFID), or type the card number, and press Enter.
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
*Return to Fiction Section · Aisle 3 · Shelf 2*, or *Place on hold shelf for (name)*. Overdue fines
are added automatically (only days the library was open are counted).

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

### 1.7 Fines and payments
**Circulation → Fines & payments**: select unpaid fines and **Pay** to issue an official receipt
(reprint any time), or **Waive** with a reason (librarian). Manual charges (damaged item, lost card)
can be added from the patron's page.

### 1.8 Catalog
**Collection → Catalog**

- **New record**: type the ISBN and press **Lookup** (Open Library), or **LoC** to search the Library of
  Congress catalog by ISBN, title or author. Check the copied data, then save.
- Authors and subjects suggest existing headings as you type, which keeps the catalog consistent.
  Variant names are managed under **Authorities**.
- Add **copies** (barcode, RFID EPC, call number, section, aisle, shelf, price). Mark room-use-only
  material types under Administration.
- **E-book / digital copy** panel: upload a school-owned PDF/EPUB, or link a commercial e-book.
- **Import** a CSV or MARC file. A preview runs first; nothing is saved until you confirm.
  **Export** the catalog as Excel (CSV), PDF, MARC21 or MARCXML.

### 1.9 Patrons
**People → Patrons → New patron**: card number (scan the ID's barcode or tap the RFID card into the
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

### 1.12 Stocktake
**Collection → Stocktake**: start a session for a section, scan every item on the shelves (scanner,
RFID pad or camera), then open the report: *missing*, *misplaced*, *unexpected status* and *unknown codes*.
Closing the session can mark unscanned items MISSING; scanned LOST/MISSING items become AVAILABLE.
**Download report…** as Excel or PDF.

### 1.13 Reports
**Security & reports → Reports**: choose the period, view circulation and visitor charts, top
borrowers and titles, and export loans, overdue, fines, collection, patrons, attendance and gate
events. Use the format picker to choose **Excel (CSV)** or **PDF**.

---

## 2. Patrons

### 2.1 Searching the catalog (OPAC)
Open `/opac` on any device or use a library kiosk. Search by title, author, subject or ISBN. Each copy shows
whether it is **Available** or **On loan** and where it is shelved. School-owned e-books open in the reader.

### 2.2 My Library (`/my`)
Sign in with your card number and PIN (get the PIN at the desk). You can:

- see your loans and due dates, and **renew** (unless overdue or at the renewal limit);
- **reserve** a title, or **book** it for a pickup date from the availability calendar;
- see fines and receipts;
- read school-owned e-books;
- change your PIN;
- **Download my data**: a copy of everything the library keeps about you.

### 2.3 Attendance kiosk
Tap your ID card on the reader when you enter (and leave, if the library uses time-out). No card?
Press **No card? Enter ID**.

---

## 3. Your privacy

The library's privacy notice is at `/privacy` and linked on the sign-in, OPAC and home pages.
To see, correct, download or erase your data, contact the Data Protection Officer named in the notice,
or download your own copy in **My Library → Account**.

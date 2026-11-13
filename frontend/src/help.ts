/**
 * "How to use" text behind the (i) next to each staff page title, keyed by route (the part after /staff/).
 * Use the words shown on the screen; wrap screen labels in **double asterisks** to show them in bold.
 */
export interface Help {
  title: string
  /** What the module is for. */
  about: string
  /** Who uses it (roles). */
  who: string
  sections: { heading: string; steps: string[] }[]
  /** How it connects to other modules: module name and what passes between them. */
  connects?: [string, string][]
}

export const HELP: Record<string, Help> = {
  '': {
    title: 'Dashboard',
    about: "Your start page. It shows today's activity and the state of the library at a glance.",
    who: 'Every staff user. Cashiers start on Fines & payments instead.',
    sections: [
      { heading: 'Check today', steps: [
        'Open **Dashboard** from the menu. The **Today** tiles show check-outs, returns, library visits, people **Inside now** and fines collected.',
        'A figure in red needs attention, e.g. overdue items or missing copies.',
      ] },
      { heading: 'See the bigger picture', steps: [
        '**Circulation** shows items on loan, overdue, holds waiting and holds ready on the hold shelf.',
        '**Collection** shows titles, copies, and copies marked lost or missing.',
      ] },
      { heading: 'Go straight to work', steps: [
        'Click **Circulation desk** to lend and return items.',
        'Click **New record** to add a title to the catalog.',
      ] },
    ],
    connects: [
      ['Check out / in', 'every check-out and return updates the Today figures.'],
      ['Attendance', 'library visits and Inside now come from the attendance kiosk.'],
      ['Reports', 'for figures over a period, with filters and downloads.'],
    ],
  },
  circulation: {
    title: 'Check out / in',
    about: 'The circulation desk: lend items, take returns, renew loans and release reservations.',
    who: 'Staff, librarians and administrators at the desk.',
    sections: [
      { heading: 'Lend items (check out)', steps: [
        "On **Check out / renew**, scan or type the patron's library ID, or search by name.",
        'Check the patron panel: items on loan, fines, and any block (expired ID, too many items, unpaid fines).',
        'Scan each item. It appears under **This session** with its due date.',
        'If a limit stops the loan, the desk says why. A librarian can override it.',
        'Press **Print receipt** only if the patron asks for one.',
      ] },
      { heading: 'Take returns (check in)', steps: [
        'Open **Check in (return)** and scan the item.',
        'If the borrowing has fines, they must be paid at the cashier (or waived) before the return completes. Use **Send to cashier**.',
        'Follow the message: return the item to its shelf, or **Place on hold shelf for…** and **Print hold slip**.',
      ] },
      { heading: 'Renew', steps: [
        "Under the patron's **Current loans**, press **Renew**.",
        "Overdue items and titles another patron reserved can't be renewed.",
      ] },
      { heading: 'When the network is down', steps: [
        'Check-outs and returns are saved on this computer and sent when the server is back.',
        'Review anything listed under **Offline transactions that need attention**.',
      ] },
    ],
    connects: [
      ['Holds', 'a returned copy goes to the next patron waiting; releasing a held copy is a check-out here.'],
      ['Fines & payments', 'overdue fines are charged at return and paid at the cashier.'],
      ['Loan rules & courses', 'sets the loan period, item limits and fines used here.'],
      ['Library calendar', 'due dates skip closed days.'],
      ['RFID station on this computer', 'switches the tag security off at check-out and on at return, if a pad is set up.'],
    ],
  },
  loans: {
    title: 'Loans & overdue',
    about: 'Every item out right now, and the ones past their due date.',
    who: 'Staff, librarians and administrators.',
    sections: [
      { heading: 'Follow up late items', steps: [
        'Open **Overdue** to see items past due, who has them, and the fine so far.',
        'Contact the patron, or let the automatic overdue notices remind them.',
        'Press **Export overdue…** for a list in Excel, CSV or PDF.',
      ] },
      { heading: 'See everything on loan', steps: ['Open **All active loans** for every item out and its due date.'] },
    ],
    connects: [
      ['Check out / in', 'loans start and end there.'],
      ['Notifications', 'sends the due-soon and overdue notices.'],
      ['Reports', 'the Circulation tab has the loans and overdue reports with filters.'],
    ],
  },
  renewals: {
    title: 'Renewal requests',
    about: 'Renewals patrons asked for in the portal, the app or the kiosk that need a librarian to decide.',
    who: 'Librarians and administrators.',
    sections: [
      { heading: 'Decide on a request', steps: [
        'Read the request: title, patron, current due date and the new one if approved.',
        'Press **Approve** to extend the loan.',
        'Or **Reject** and write a **Reason for the patron**. They see it in the portal and app.',
      ] },
      { heading: 'Look back', steps: ['Open **Decided** for past requests and who decided them.'] },
    ],
    connects: [
      ['Loan rules & courses', '**Patron renewals** sets whether patrons renew on their own or need approval.'],
      ['Patron portal and app', 'where patrons ask, and see the answer.'],
    ],
  },
  holds: {
    title: 'Holds',
    about: 'Reservations: copies waiting on the hold shelf, patrons waiting in line, and bookings for a date.',
    who: 'Staff, librarians and administrators.',
    sections: [
      { heading: 'Understand the lists', steps: [
        '**Waiting for a copy**: patrons in line, in order. A returned copy goes to #1 automatically.',
        '**On the hold shelf**: copies set aside for a patron, with the pickup deadline.',
        '**Booked for later**: reservations for a chosen pickup date. They join the line that morning, ahead of others.',
      ] },
      { heading: 'Hand over a reserved copy', steps: [
        'Find the copy on the hold shelf (its hold slip shows the ID number and initials).',
        'Check it out to that patron at **Check out / in**. The reservation is closed.',
      ] },
      { heading: 'When nobody comes', steps: [
        'After the pickup deadline the reservation expires on its own.',
        'The copy goes to the next patron in line, or back to the shelf.',
      ] },
      { heading: 'Other actions', steps: ['**Cancel** removes a reservation. **Print hold slip** labels a set-aside copy.'] },
    ],
    connects: [
      ['Check out / in', 'returns feed the hold shelf; check-outs release held copies.'],
      ['Loan rules & courses', 'sets the pickup window and how many reservations a patron may have.'],
      ['Notifications', 'tells the patron the copy is ready.'],
    ],
  },
  fines: {
    title: 'Fines & payments',
    about: 'Fines charged to patrons, payments received, and the end-of-day report.',
    who: 'Cashiers receive payments. Staff and librarians can view fines; librarians can waive them.',
    sections: [
      { heading: 'Receive a payment', steps: [
        'Search fines by patron: name or library ID.',
        'Select the fines being paid and press **Pay**. A receipt number is issued.',
        'Press **Print** if the patron wants a receipt. It is not an official receipt.',
        'A return waiting on these fines can now be completed at the desk.',
      ] },
      { heading: 'Close the day', steps: ['Open **End-of-day report** for the payments received today, for the cash count.'] },
      { heading: 'Find fines', steps: ['Use **Unpaid**, **Paid**, **Waived** or **All** to filter the list.'] },
    ],
    connects: [
      ['Check out / in', 'overdue fines are charged at return; the return completes after payment.'],
      ['Patrons', '**Add charge** on a patron adds damage or other fines.'],
      ['Loan rules & courses', 'sets the fine per day, the maximum, and the unpaid limit that blocks borrowing.'],
      ['Audit trail', 'records every payment and waiver.'],
    ],
  },
  catalog: {
    title: 'Catalog',
    about: 'All titles in the collection and their copies.',
    who: 'Staff and librarians. Only librarians delete titles.',
    sections: [
      { heading: 'Find a title', steps: [
        'Type in **Search catalog**. Choose a **Search field** or **Material type** to narrow it.',
        'Click a row to open the title, its copies and its borrowing history.',
      ] },
      { heading: 'Add a title', steps: [
        'Press **New record**.',
        'Type or scan the ISBN and press **Lookup** to fill in the details from online catalogs (Library of Congress and others).',
        'Check the details, add the call number, and save.',
        "Press **Add copy** for each physical copy. Its barcode is made when you save.",
      ] },
      { heading: 'Import and export', steps: [
        '**Import** loads titles from a CSV, MARC21 (.mrc) or MARCXML file.',
        '**Export…** downloads the catalog as Excel, CSV, PDF or MARC.',
      ] },
    ],
    connects: [
      ['Labels & library IDs', 'prints barcode and spine labels for new copies.'],
      ['Sections & Aisles', 'the sections, aisles and shelves a copy can be placed on.'],
      ['Authorities', 'suggests the agreed form of author and subject names.'],
      ['Catalog fields', 'adds the library\'s own fields to every record.'],
      ['OPAC', 'patrons search this catalog on the kiosks, portal and app.'],
    ],
  },
  'catalog/record': {
    title: 'Title and copies',
    about: 'One title: its catalog details, its copies, and who borrowed them.',
    who: 'Staff and librarians. Only librarians delete.',
    sections: [
      { heading: 'Edit the details', steps: ['Press **Edit** to change the title, authors, ISBN, call number, subjects, summary and custom fields, then save.'] },
      { heading: 'Add or change copies', steps: [
        'Press **Add copy**. Choose the **Section / area**, **Aisle** and **Shelf**, the status, price and date acquired.',
        'The barcode is generated when you save.',
        'With an RFID station, write the copy\'s barcode to a tag, or read a tag to fill in **RFID tag EPC**.',
        'Press **Print labels** for the barcode and spine labels.',
      ] },
      { heading: 'See its history', steps: ['**Borrowing history** shows who borrowed each copy, when, and who has it now.'] },
    ],
    connects: [
      ['Holds', 'patrons reserve this title from the catalog.'],
      ['Stocktake', 'checks the copies are on the shelf recorded here.'],
    ],
  },
  inventory: {
    title: 'Stocktake',
    about: 'Shelf inventory: scan what is on the shelves and compare it with what should be there.',
    who: 'Staff and librarians.',
    sections: [
      { heading: 'Start', steps: [
        'Press **Start stocktake**. Give it a **Name** and choose the **Section / area** (and aisle).',
        'Optionally press **Print shelf list…** for a paper list to check against.',
      ] },
      { heading: 'Scan the shelves', steps: [
        'Scan every copy on the shelves with a barcode scanner or RFID reader.',
        '**Shelf list vs. scanned, by aisle** shows **Expected on shelf**, **Scanned** and **Not scanned yet** for each aisle.',
      ] },
      { heading: 'Read the results', steps: [
        '**Found**: on the shelf where it belongs. **Misplaced**: found in another section or aisle.',
        '**Missing**: expected but not scanned. **Status mismatch**: e.g. scanned but recorded as on loan.',
        '**Unknown codes**: scanned codes that match no copy.',
        'Press **Download report…** for the results.',
      ] },
    ],
    connects: [
      ['Sections & Aisles', 'the aisles listed come from there.'],
      ['Catalog', 'each copy\'s recorded section, aisle and shelf is what "expected" means.'],
    ],
  },
  authorities: {
    title: 'Authorities',
    about: 'One agreed form for each author and subject, so the same name is not spelled three ways.',
    who: 'Librarians and cataloguing staff.',
    sections: [
      { heading: 'Add a heading', steps: [
        'Choose **Authors** or **Subjects** and add a **Preferred heading**.',
        'Under **Variant forms (one per line)** list other spellings people use.',
      ] },
      { heading: 'How it helps', steps: [
        'The catalog form suggests the preferred heading as you type.',
        'Searching for a variant finds titles filed under the preferred heading.',
      ] },
    ],
    connects: [['Catalog', 'authors and subjects on each record.']],
  },
  labels: {
    title: 'Labels & library IDs',
    about: 'Print barcode and spine labels for copies, and library ID cards for patrons.',
    who: 'Staff and librarians.',
    sections: [
      { heading: 'Print copy labels', steps: [
        'Scan or type the copies\' **Barcodes**, or choose **…or copies added since** a date (e.g. today\'s new books).',
        'Choose the **Label type** and the sheet in **Print on**.',
        'On a partly used sheet, set **Skip labels** to the number already used.',
        'Press **Print labels**.',
      ] },
      { heading: 'Print library IDs', steps: [
        'Choose patrons by **ID numbers**, **Category**, **Program** or **Registered since**.',
        'Set **Skip IDs** for a partly used sheet, then press **Print library IDs**.',
      ] },
    ],
    connects: [
      ['Catalog', 'copies and their barcodes and call numbers.'],
      ['Sections & Aisles', 'spine labels show the shelf address.'],
      ['Patrons', 'names, photos and ID numbers on the cards.'],
    ],
  },
  patrons: {
    title: 'Patrons',
    about: 'Students, faculty, staff and other borrowers.',
    who: 'Staff, librarians and administrators.',
    sections: [
      { heading: 'Register a patron', steps: [
        'Press **Register**. Leave the library ID blank to have one generated.',
        'Choose the category (it sets the loan rules), program and expiry date, and save.',
        'Open the patron to add a photo, set a PIN (for the kiosk, portal and app) and print the library ID.',
      ] },
      { heading: 'Register many at once', steps: ['Press **Template**, fill it in, then **Bulk import CSV**.'] },
      { heading: 'Clearance', steps: ['Press **Verify clearance** to check a patron has no loans or unpaid fines, e.g. before graduation. A **Clearance number** is given.'] },
    ],
    connects: [
      ['Loan rules & courses', 'categories and programs patrons belong to.'],
      ['Labels & library IDs', 'prints ID cards.'],
      ['Data privacy', 'export, correct or anonymize a patron\'s data.'],
    ],
  },
  'patrons/detail': {
    title: 'Patron details',
    about: 'One patron: what they have, what they owe, and their history.',
    who: 'Staff, librarians and administrators.',
    sections: [
      { heading: 'Loans', steps: ['Under **On loan now**: **Renew**, **Return**, or mark an item **Lost**.'] },
      { heading: 'Reservations', steps: ['See what is **On the hold shelf**, **Waiting for a copy** and **Booked for later** for this patron.'] },
      { heading: 'Charges', steps: [
        'Press **Add charge** for damage or similar. Choose **For which borrowing?** if it is about a borrowed item.',
        'The return of that item then waits until the charge is paid.',
      ] },
      { heading: 'Account', steps: [
        '**Issue and print** the library ID, set a **New PIN**, change the photo.',
        'See **Attendance**, **History** and **Data privacy** actions.',
      ] },
    ],
    connects: [
      ['Fines & payments', 'charges added here are paid at the cashier.'],
      ['Check out / in', 'the same loans, from the desk.'],
    ],
  },
  attendance: {
    title: 'Attendance',
    about: 'Library visits recorded by the attendance kiosk (time in and time out).',
    who: 'Staff, librarians and administrators.',
    sections: [
      { heading: 'See who came', steps: [
        '**Inside now**: who is in the library.',
        '**Logs**: every visit, with time in and out.',
        '**Statistics**: **Total visits** and **Unique patrons** for a period. **Download report…** for a file.',
      ] },
      { heading: 'Set up the kiosk', steps: [
        '**Tap behaviour**: the first tap is time in and the next time out; **Single-tap mode** records time in only.',
        'Write the **Entry message** and **Exit message**, the **Information board messages**, and a **Stand-by video URL**.',
      ] },
    ],
    connects: [
      ['Users & devices', 'the attendance kiosk device and its setup link.'],
      ['Reports', 'Patrons & attendance tab.'],
    ],
  },
  gate: {
    title: 'Security gate',
    about: 'The RFID security gate: alarms, gate status, announcements and the event log.',
    who: 'Staff can watch; librarians and administrators change settings.',
    sections: [
      { heading: 'Gate control & broadcast', steps: [
        'See each gate **Online** or **Offline** and when it was last seen.',
        'Set the alarm schedule, or **Disable alarm** / **Enable alarm**.',
        '**Emergency / evacuation mode** silences the alarms and never blocks people.',
        'Under **Announcement broadcast**, type a message and press **Broadcast**, or press a preset.',
      ] },
      { heading: 'Announcement voice', steps: ['Choose the voice, speed and pitch the kiosks use to read announcements.'] },
      { heading: 'Event log', steps: ['Alarms, visitor counts, tamper and power events. Filter by event and date.'] },
    ],
    connects: [
      ['Users & devices', 'each gate is a device with a token; **How to connect a gate** is in its dialog.'],
      ['Check out / in', 'switches tag security so borrowed items pass quietly.'],
      ['Reports', 'Security gate tab.'],
    ],
  },
  reports: {
    title: 'Reports',
    about: 'Statistics and downloadable reports, grouped by sub-module.',
    who: 'Staff, librarians and administrators. Data migration is for librarians and administrators.',
    sections: [
      { heading: 'Get a report', steps: [
        'Pick a tab: **Circulation**, **Collection**, **Fines & payments**, **Patrons & attendance**, **Security gate**, **E-book usage** or **Data migration**.',
        'Set the **Filters**. Only the filters that tab\'s reports use are shown; **Not used by** says which report ignores one.',
        'Read the summary line and the preview: they are exactly what the file will contain.',
        'Press **Excel**, **CSV** or **PDF** on the report.',
      ] },
    ],
    connects: [['Every module', 'reports read the same records the other modules create.']],
  },
  'print-log': {
    title: 'Print log',
    about: 'Every receipt, slip and ID printed, by whom and when.',
    who: 'Staff, librarians and administrators.',
    sections: [{ heading: 'Find a printout', steps: ['Filter by **From** and **To** dates or **Search**.', 'Open a **Document** to see or reprint it.'] }],
  },
  settings: {
    title: 'Loan rules & courses',
    about: 'How long items are lent, how many, the fines, and the programs patrons belong to.',
    who: 'Librarians and administrators change them; staff can view.',
    sections: [
      { heading: 'Patron categories (loan rules)', steps: [
        'For each category (Student, Faculty…), set **Loan period (days)**, **Max items on loan**, **Max renewals**, **Max reservations and bookings** and **Pickup window (days)**.',
        'Set **Overdue fine per day (PHP)**, **Grace period (days)**, **Max overdue fine per item (PHP)** and the **Unpaid fines limit (PHP)** that blocks borrowing.',
      ] },
      { heading: 'By material type', steps: ['Give e.g. reference books or audiovisual items their own loan period.'] },
      { heading: 'Library-wide rules', steps: ['**Default lost-item charge (PHP)**, **Bookings: how far ahead (days)**, the at-risk warning, and **Patron renewals**.'] },
      { heading: 'Programs / courses', steps: ['Add each program with its **Code (e.g. BSMT)** and **Name**.'] },
    ],
    connects: [
      ['Check out / in', 'applies these rules to every loan.'],
      ['Patrons', 'each patron has a category and program.'],
    ],
  },
  calendar: {
    title: 'Library calendar',
    about: 'Opening hours and closed days.',
    who: 'Librarians and administrators change it; staff can view.',
    sections: [
      { heading: 'Opening hours', steps: ['Tick the days the library opens, set the times, and press **Save hours**.'] },
      { heading: 'Closed days & holidays', steps: [
        'Press **Add Philippine holidays**, pick the year, and add the national holidays in one go.',
        'For other days, enter the **Date** and **Reason** and press **Add closed day**.',
        "Add Eid'l Fitr, Eid'l Adha, local holidays and school events by hand once announced.",
      ] },
    ],
    connects: [
      ['Check out / in', 'due dates and pickup deadlines move to the next open day.'],
      ['Fines & payments', 'overdue fines count only open days.'],
      ['Attendance', 'visitors who forget to tap out are timed out at closing time.'],
    ],
  },
  'catalog-fields': {
    title: 'Catalog fields',
    about: 'Extra fields the library adds to every catalog record, e.g. accession number, donor or thesis adviser.',
    who: 'Librarians and administrators.',
    sections: [
      { heading: 'Add a field', steps: [
        'Press **Add field** and give it a **Label**.',
        'Choose the **Type**: Text, Number, Date, or Choice list (one choice per line).',
        '**Order** sets its position on the record form.',
        'Tick **Show in OPAC** for patrons to see it; untick for staff-only data like donor or price.',
      ] },
    ],
    connects: [['Catalog', 'fill in the values on each title under Edit.']],
  },
  sections: {
    title: 'Sections & Aisles',
    about: 'Where copies are shelved: sections or areas, their aisles, and the shelves in each aisle.',
    who: 'Administrators.',
    sections: [
      { heading: 'Set up the shelving', steps: [
        'Type a **New section name** (e.g. Fiction Section, Filipiniana) and press **Add section**.',
        'Add aisles: **How many aisles** (numbered) or **Or one aisle named**, and the **Shelves in each aisle**.',
      ] },
    ],
    connects: [
      ['Catalog', 'the copy form only offers these sections, aisles and shelves.'],
      ['Stocktake', 'compares the shelves aisle by aisle.'],
      ['Labels & library IDs', 'spine labels show the shelf address.'],
    ],
  },
  notifications: {
    title: 'Notifications',
    about: 'E-mail and SMS notices to patrons: due-soon reminders, overdue notices, reservations ready.',
    who: 'Librarians and administrators.',
    sections: [
      { heading: 'Connect e-mail', steps: [
        'Under **E-mail (SMTP)**, enter the **SMTP server**, **Port**, **Username**, **Password**, **Sender address** and **Sender name**.',
        'Press **Send a test** to check it.',
      ] },
      { heading: 'Connect SMS (optional)', steps: ['Under **SMS gateway**, enter the **Gateway URL** and **API key**.'] },
      { heading: 'Schedule & messages', steps: ['Set the **Daily send time**, the **Due-soon reminder**, and the wording of each notice.'] },
      { heading: 'Check what was sent', steps: ['**Recent notices** lists each notice, the patron, and whether it went through.'] },
    ],
    connects: [
      ['Holds', 'reservation-ready notices.'],
      ['Loans & overdue', 'due-soon and overdue notices.'],
    ],
  },
  users: {
    title: 'Users & devices',
    about: 'Staff accounts, and the kiosks and security gates that connect to the library system.',
    who: 'Administrators.',
    sections: [
      { heading: 'Staff users', steps: [
        'Press **Add user**: **Full name**, **Username** and **Role** (Administrator, Librarian, Staff, Cashier).',
        'Open a user to change the role, set a temporary password (they choose a new one at sign-in), or disable the account.',
      ] },
      { heading: 'Devices', steps: [
        'Press **Add device** for an attendance kiosk, self-checkout kiosk or security gate.',
        'A kiosk gets a **Device key** and setup links to open on the kiosk PC.',
        'A gate gets a device token: press **Issue new device token**, then follow **How to connect a gate (any brand)**.',
      ] },
      { heading: 'Sign-in protection', steps: ['Set **Failed attempts before locking** and **Lock duration (minutes)**.'] },
    ],
    connects: [
      ['Attendance and Security gate', 'the devices set up here report there.'],
      ['Audit trail', 'records sign-ins and changes to accounts.'],
    ],
  },
  audit: {
    title: 'Audit trail',
    about: 'A permanent record of who did what: sign-ins, check-outs, overrides, waivers, settings changes.',
    who: 'Administrators.',
    sections: [{ heading: 'Find an action', steps: ['Filter by **User**, **Action**, **From** and **To**, or use **Search details**.'] }],
    connects: [['Data privacy', 'sets how long the audit trail is kept.']],
  },
  privacy: {
    title: 'Data privacy',
    about: 'Data Privacy Act (RA 10173): the privacy notice, the Data Protection Officer, and how long records are kept.',
    who: 'Administrators.',
    sections: [
      { heading: 'Notice and officer', steps: ['Write the **Notice shown to patrons** and the **Data Protection Officer** with a contact.'] },
      { heading: 'Retention limits', steps: ['Set how long to keep loan, fine and reservation history, library visits, the security gate log, sent notices, the audit trail, and expired patrons.'] },
      { heading: 'Patron requests', steps: ["Open the patron to export their data, correct it, or anonymize them."] },
    ],
    connects: [['Patrons', 'the data these rules apply to.']],
  },
  system: {
    title: 'System settings',
    about: 'The library profile, receipts, kiosks, upload limits and sign-in sessions.',
    who: 'Administrators.',
    sections: [
      { heading: 'Library profile', steps: ['**Library name**, **Short name**, **School / institution**, **Address**, **Phone**, **E-mail**, **Website**. Used on receipts, IDs and reports.'] },
      { heading: 'Receipts', steps: ['**Receipt header** and **Receipt footer** text.'] },
      { heading: 'Kiosks', steps: ['**OPAC kiosk reset (seconds)**, **Ignore repeat taps (seconds)**, and the **Self-checkout** options.'] },
      { heading: 'Uploads and sign-in', steps: [
        '**Upload limits (MB)** for e-books, patron photos and announcement recordings.',
        '**Sign-in sessions**: how long staff stay signed in, and the gate device token lifetime.',
      ] },
    ],
  },
  account: {
    title: 'My account',
    about: 'Your own staff account.',
    who: 'Every staff user.',
    sections: [{ heading: 'Change your password', steps: ['Enter your **Current password**, then the **New password** twice, and save.'] }],
  },
}

/** The help for a staff route such as "/staff/catalog/12". */
export function helpFor(pathname: string): Help | undefined {
  const path = pathname.replace(/^\/staff\/?/, '').replace(/\/+$/, '')
  if (/^catalog\/(new|\d+)(\/edit)?$/.test(path)) return HELP['catalog/record']
  if (/^patrons\/\d+$/.test(path)) return HELP['patrons/detail']
  return HELP[path]
}

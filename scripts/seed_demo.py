#!/usr/bin/env python3
"""
Seeds demo data through the REST API (so every business rule applies):
20 Nicholas Sparks novels with copies, dummy titles for every other material type, 20 patrons,
circulation/attendance activity and stocktake sessions.

Safe to re-run: existing titles, copies, patrons and courses are skipped.

Usage:
    scripts/seed_demo.py [--api http://localhost:8080] [--user admin] [--password password123]
"""
import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BOOKS = [
    # title, year, publisher, subjects, summary
    ("The Notebook", 1996, "Warner Books", "Romance fiction; Love stories; North Carolina -- Fiction",
     "An elderly man reads a story of young summer love to a woman in a care home."),
    ("Message in a Bottle", 1998, "Warner Books", "Romance fiction; Widowers -- Fiction",
     "A journalist finds a love letter in a bottle and searches for the man who wrote it."),
    ("A Walk to Remember", 1999, "Warner Books", "Romance fiction; Teenagers -- Fiction",
     "A popular high-school senior is drawn to the minister's quiet daughter in a small town."),
    ("The Rescue", 2000, "Warner Books", "Romance fiction; Firefighters -- Fiction",
     "A volunteer firefighter helps a single mother search for her son during a storm."),
    ("A Bend in the Road", 2001, "Warner Books", "Romance fiction; Widowers -- Fiction",
     "A sheriff whose wife died in a hit-and-run falls for his son's teacher."),
    ("Nights in Rodanthe", 2002, "Warner Books", "Romance fiction; Outer Banks (N.C.) -- Fiction",
     "Two strangers meet at a coastal inn during a storm weekend."),
    ("The Guardian", 2003, "Warner Books", "Romance fiction; Suspense fiction",
     "A young widow is torn between two men while her dog keeps watch over her."),
    ("The Wedding", 2003, "Warner Books", "Romance fiction; Marriage -- Fiction",
     "A husband tries to win back his wife's love before their daughter's wedding."),
    ("True Believer", 2005, "Warner Books", "Romance fiction; Journalists -- Fiction",
     "A skeptical science writer investigates mysterious lights in a cemetery."),
    ("At First Sight", 2005, "Warner Books", "Romance fiction; Journalists -- Fiction",
     "The sequel to True Believer follows the couple's new life together."),
    ("Dear John", 2006, "Grand Central Publishing", "Romance fiction; Soldiers -- Fiction",
     "A soldier on leave falls in love, and their bond is tested by distance and duty."),
    ("The Choice", 2007, "Grand Central Publishing", "Romance fiction; Neighbors -- Fiction",
     "A bachelor's life changes when a new neighbor moves in next door."),
    ("The Lucky One", 2008, "Grand Central Publishing", "Romance fiction; Veterans -- Fiction",
     "A Marine searches for the woman in a photograph he believes kept him safe."),
    ("The Last Song", 2009, "Grand Central Publishing", "Romance fiction; Fathers and daughters -- Fiction",
     "A rebellious teenager spends a summer with her estranged father by the sea."),
    ("Safe Haven", 2010, "Grand Central Publishing", "Romance fiction; Suspense fiction",
     "A woman with a secret past starts over in a small coastal town."),
    ("The Best of Me", 2011, "Grand Central Publishing", "Romance fiction; First loves -- Fiction",
     "Former high-school sweethearts reunite years later for a mentor's funeral."),
    ("The Longest Ride", 2013, "Grand Central Publishing", "Romance fiction; Rodeos -- Fiction",
     "Two love stories, decades apart, intertwine after a car accident."),
    ("See Me", 2015, "Grand Central Publishing", "Romance fiction; Suspense fiction",
     "A young lawyer and a man with a troubled past face threats from a stranger."),
    ("Two by Two", 2016, "Grand Central Publishing", "Domestic fiction; Fathers and daughters -- Fiction",
     "A father rebuilds his life around his young daughter after his marriage ends."),
    ("Every Breath", 2018, "Grand Central Publishing", "Romance fiction; Love letters -- Fiction",
     "Two strangers meet by chance at a mailbox on the beach where people leave letters."),
]

COURSES = [
    ("BSMT", "BS Marine Transportation"),
    ("BSMARE", "BS Marine Engineering"),
    ("BSFI", "BS Fisheries"),
    ("BSMB", "BS Marine Biology"),
    ("BSIT", "BS Information Technology"),
    ("BSED", "Bachelor of Secondary Education"),
]

# card, first, last, category, course, email
PATRONS = [
    ("2025-0101", "Maria", "Santos", "Student", "BSMT"),
    ("2025-0102", "Jose", "Reyes", "Student", "BSMT"),
    ("2025-0103", "Angelica", "Cruz", "Student", "BSMARE"),
    ("2025-0104", "Mark Anthony", "Bautista", "Student", "BSMARE"),
    ("2025-0105", "Kristine", "Ocampo", "Student", "BSFI"),
    ("2025-0106", "John Paul", "Garcia", "Student", "BSFI"),
    ("2025-0107", "Princess", "Mendoza", "Student", "BSMB"),
    ("2025-0108", "Rafael", "Torres", "Student", "BSMB"),
    ("2025-0109", "Jasmine", "Villanueva", "Student", "BSIT"),
    ("2025-0110", "Christian", "Ramos", "Student", "BSIT"),
    ("2025-0111", "Nur-Aina", "Hassan", "Student", "BSED"),
    ("2025-0112", "Abdul", "Jalani", "Student", "BSED"),
    ("2025-0113", "Kimberly", "Aquino", "Student", "BSMT"),
    ("2025-0114", "Ronaldo", "Fernandez", "Student", "BSIT"),
    ("F-0201", "Liza", "Navarro", "Faculty", "BSMT"),
    ("F-0202", "Eduardo", "Castillo", "Faculty", "BSMARE"),
    ("F-0203", "Rowena", "Salazar", "Faculty", "BSFI"),
    ("S-0301", "Ramon", "Delos Santos", "Staff", None),
    ("S-0302", "Teresita", "Lim", "Staff", None),
    ("A-0401", "Bernadette", "Uy", "Alumni", "BSMB"),
]


# Other material types: (type, prefix, location, copies, [(title, authors, publisher, year, edition, subjects, summary)])
OTHER_MATERIALS = [
    ("AUDIOVISUAL", "AV", "Audio-Visual Room", 2, [
        ("The Notebook (DVD)", "Cassavetes, Nick, director", "New Line Home Entertainment", 2004, "Widescreen DVD",
         "Feature films; Romance films; Film adaptations", "Film adaptation of the Nicholas Sparks novel."),
        ("A Walk to Remember (DVD)", "Shankman, Adam, director", "Warner Home Video", 2002, "DVD",
         "Feature films; Romance films; Film adaptations", "Film adaptation of the Nicholas Sparks novel."),
        ("Dear John (DVD)", "Hallstrom, Lasse, director", "Sony Pictures Home Entertainment", 2010, "DVD",
         "Feature films; Romance films; Film adaptations", "Film adaptation of the Nicholas Sparks novel."),
    ]),
    ("SERIAL", "SR", "Periodicals Section", 1, [
        ("National Geographic", None, "National Geographic Partners", 2025, "October 2025 issue",
         "Geography -- Periodicals; Natural history -- Periodicals", "Monthly magazine (dummy issue record)."),
        ("Reader's Digest", None, "Trusted Media Brands", 2025, "November 2025 issue",
         "General interest periodicals", "Monthly general-interest magazine (dummy issue record)."),
        ("TIME", None, "Time USA", 2025, "November 2025 issue",
         "News -- Periodicals; Current events -- Periodicals", "Weekly news magazine (dummy issue record)."),
    ]),
    ("THESIS", "TH", "Thesis & Research Section", 1, [
        ("Seaweed Farming Practices of Coastal Communities in Zamboanga City", "Hassan, Nur-Aina; Jalani, Abdul",
         "ZSCMST College of Education", 2024, None, "Seaweed culture; Coastal communities; Undergraduate theses",
         "Dummy undergraduate thesis record for demonstration."),
        ("Fuel Efficiency of Inter-Island Passenger Vessels in the Zamboanga Peninsula",
         "Bautista, Mark Anthony; Cruz, Angelica", "ZSCMST College of Maritime Education", 2025, None,
         "Ships -- Fuel consumption; Undergraduate theses", "Dummy undergraduate thesis record for demonstration."),
        ("A Mobile Application for Recording Fish Catch of Small-Scale Fisherfolk", "Garcia, John Paul; Villanueva, Jasmine",
         "ZSCMST College of Computing Studies", 2025, None, "Fisheries -- Data processing; Undergraduate theses",
         "Dummy undergraduate thesis record for demonstration."),
    ]),
    # Commercial e-books: no copies and no file; the record links to the vendor (placeholder address).
    ("EBOOK", "EB", None, 0, [
        ("The Wish", "Sparks, Nicholas", "Grand Central Publishing", 2021, "E-book",
         "Romance fiction; Electronic books", "Commercial e-book, read on the vendor's platform."),
        ("Dreamland", "Sparks, Nicholas", "Random House", 2022, "E-book",
         "Romance fiction; Electronic books", "Commercial e-book, read on the vendor's platform."),
        ("Counting Miracles", "Sparks, Nicholas", "Random House", 2024, "E-book",
         "Romance fiction; Electronic books", "Commercial e-book, read on the vendor's platform."),
    ]),
    ("REFERENCE", "RF", "Reference Section", 1, [
        ("Merriam-Webster's Collegiate Dictionary", None, "Merriam-Webster", 2020, "11th ed.",
         "English language -- Dictionaries", "Room-use reference copy."),
        ("Oxford Advanced Learner's Dictionary", None, "Oxford University Press", 2020, "10th ed.",
         "English language -- Dictionaries", "Room-use reference copy."),
        ("National Geographic Atlas of the World", None, "National Geographic", 2019, None,
         "Atlases", "Room-use reference copy."),
    ]),
    ("OTHER", "OT", "Special Collections", 1, [
        ("Celestial Navigation Globe", None, None, 2023, None, "Celestial navigation -- Teaching aids",
         "Realia: star globe used in navigation classes (dummy record)."),
        ("Ship Model: Traditional Vinta of Zamboanga", None, None, 2022, None, "Ship models; Vinta (Boat)",
         "Realia: scale model of the vinta sailboat (dummy record)."),
        ("Nautical Chart Set: Zamboanga and Basilan Waters", None, None, 2024, None, "Nautical charts",
         "Realia: laminated chart set for chartwork practice (dummy record)."),
    ]),
]


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.token = None

    def call(self, method, path, body=None, ok_conflict=False):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(req) as res:
                text = res.read().decode()
                return json.loads(text) if text else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode()
            if ok_conflict and e.code in (400, 409):
                return None
            raise SystemExit(f"{method} {path} -> {e.code}: {detail}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="password123")
    args = ap.parse_args()

    api = Api(args.api)
    api.token = api.call("POST", "/api/auth/login", {"username": args.user, "password": args.password})["accessToken"]

    # Courses
    courses = {c["code"]: c["id"] for c in api.call("GET", "/api/courses")}
    for code, name in COURSES:
        if code not in courses:
            courses[code] = api.call("POST", "/api/courses", {"code": code, "name": name})["id"]
    categories = {c["name"]: c["id"] for c in api.call("GET", "/api/patron-categories")}

    # Books + copies
    records = {}
    for i, (title, year, publisher, subjects, summary) in enumerate(BOOKS, start=1):
        found = api.call("GET", "/api/catalog/records?field=title&size=50&q=" + urllib.parse.quote(title))["content"]
        match = next((r for r in found if r["title"] == title and (r["authors"] or "").startswith("Sparks")), None)
        if match is None:
            match = api.call("POST", "/api/catalog/records", {
                "title": title, "authors": "Sparks, Nicholas", "publisher": publisher, "publicationYear": year,
                "subjects": subjects, "callNumber": f"FIC SPA {year}", "materialType": "BOOK",
                "language": "English", "summary": summary,
            })
        records[title] = match["id"]
        copies = 1 + (i % 3)  # 1-3 copies per title
        for c in range(1, copies + 1):
            api.call("POST", f"/api/catalog/records/{match['id']}/items", {
                "barcode": f"LRC-NS{i:02d}{c}", "rfidEpc": f"E28011700000NS{i:02d}{c:02d}",
                "location": "Fiction Section", "status": "AVAILABLE", "acquiredDate": f"{max(year, 2015)}-06-15",
                "price": 450 + (i * 10),
            }, ok_conflict=True)

    # Every other material type
    other_count = 0
    for mtype, prefix, location, copies, titles in OTHER_MATERIALS:
        for i, (title, authors, publisher, year, edition, subjects, summary) in enumerate(titles, start=1):
            found = api.call("GET", "/api/catalog/records?field=title&size=50&q=" + urllib.parse.quote(title))["content"]
            match = next((r for r in found if r["title"] == title and r["materialType"] == mtype), None)
            if match is None:
                match = api.call("POST", "/api/catalog/records", {
                    "title": title, "authors": authors, "publisher": publisher, "publicationYear": year,
                    "edition": edition, "subjects": subjects, "callNumber": f"{prefix} {year} {i:02d}",
                    "materialType": mtype, "language": "English", "summary": summary,
                })
            records[title] = match["id"]
            other_count += 1
            if mtype == "EBOOK":
                api.call("PUT", f"/api/catalog/records/{match['id']}/digital/vendor", {
                    "url": f"https://ebooks.example.com/title/{match['id']}", "provider": "Demo e-book vendor",
                }, ok_conflict=True)
            for c in range(1, copies + 1):
                api.call("POST", f"/api/catalog/records/{match['id']}/items", {
                    "barcode": f"LRC-{prefix}{i:02d}{c}", "rfidEpc": f"E28011700000{prefix}{i:02d}{c:02d}",
                    "location": location, "status": "AVAILABLE", "acquiredDate": f"{year}-08-01",
                    "price": 300 + i * 50,
                }, ok_conflict=True)

    # Patrons
    patrons = {}
    for card, first, last, cat, course in PATRONS:
        existing = api.call("GET", "/api/patrons?q=" + urllib.parse.quote(card))["content"]
        p = next((x for x in existing if x["cardNumber"] == card), None)
        if p is None:
            p = api.call("POST", "/api/patrons", {
                "cardNumber": card, "rfidUid": "04" + card.replace("-", "").rjust(8, "0")[-8:] + "A1",
                "firstName": first, "lastName": last,
                "email": f"{first.split()[0].lower()}.{last.replace(' ', '').lower()}@zscmst.edu.ph",
                "phone": "0917" + card[-4:].rjust(7, "5"), "categoryId": categories[cat],
                "courseId": courses.get(course) if course else None, "expiryDate": "2027-06-30", "blocked": False,
            })
            api.call("POST", f"/api/patrons/{p['id']}/pin", {"pin": "1234"})
        patrons[card] = p["id"]

    # Circulation: some loans, a renewal, holds on popular titles, and attendance visits
    loans = [("2025-0101", "LRC-NS011"), ("2025-0106", "LRC-NS012"), ("2025-0103", "LRC-NS031"), ("2025-0105", "LRC-NS111"),
             ("2025-0107", "LRC-NS131"), ("2025-0109", "LRC-NS021"), ("F-0201", "LRC-NS061"),
             ("2025-0111", "LRC-NS141"), ("2025-0113", "LRC-NS161")]
    for card, barcode in loans:
        api.call("POST", "/api/circulation/checkout", {"patron": card, "item": barcode}, ok_conflict=True)
    # Both copies of The Notebook are out -> two patrons queue for it
    for card in ("2025-0102", "2025-0104"):
        api.call("POST", "/api/holds", {"patronId": patrons[card], "recordId": records["The Notebook"]}, ok_conflict=True)
    for card, _, _, _, _ in PATRONS[:16]:
        api.call("POST", "/api/kiosk/attendance/tap", {"code": card, "method": "BARCODE"}, ok_conflict=True)
    # A film out on loan too
    for card, barcode in (("2025-0108", "LRC-AV011"),):
        api.call("POST", "/api/circulation/checkout", {"patron": card, "item": barcode}, ok_conflict=True)

    # Stocktake sessions
    sessions = {s["name"]: s for s in api.call("GET", "/api/inventory/sessions")}
    on_loan = {b for _, b in loans} | {"LRC-AV011"}

    def scan_all(session_id, codes):
        for code in codes:
            api.call("POST", f"/api/inventory/sessions/{session_id}/scan", {"code": code}, ok_conflict=True)

    def type_barcodes(prefix):
        mtype = next(m for m in OTHER_MATERIALS if m[1] == prefix)
        return [f"LRC-{prefix}{i:02d}{c}" for i in range(1, len(mtype[4]) + 1) for c in range(1, mtype[3] + 1)]

    # 1) Closed, clean count of the reference shelf
    name = "Reference Section count - Oct 2026"
    if name not in sessions:
        sid = api.call("POST", "/api/inventory/sessions", {"name": name, "location": "Reference Section"})["id"]
        scan_all(sid, type_barcodes("RF"))
        api.call("POST", f"/api/inventory/sessions/{sid}/close", {"markMissing": False})

    # 2) Closed count of the AV room: one DVD not found and marked MISSING
    name = "Audio-Visual Room count - Oct 2026"
    if name not in sessions:
        sid = api.call("POST", "/api/inventory/sessions", {"name": name, "location": "Audio-Visual Room"})["id"]
        scan_all(sid, [b for b in type_barcodes("AV") if b not in on_loan and b != "LRC-AV032"])
        api.call("POST", f"/api/inventory/sessions/{sid}/close", {"markMissing": True})

    # 3) Open count of the fiction shelf with every kind of finding
    name = "Fiction Section count - Nov 2026"
    if name not in sessions:
        sid = api.call("POST", "/api/inventory/sessions", {"name": name, "location": "Fiction Section"})["id"]
        fiction = [f"LRC-NS{i:02d}{c}" for i in range(1, len(BOOKS) + 1) for c in range(1, 1 + (i % 3) + 1)]
        not_found = {"LRC-NS052", "LRC-NS183"}                    # -> missing
        scan_all(sid, [b for b in fiction if b not in on_loan and b not in not_found])
        scan_all(sid, ["LRC-NS111",                                # on loan but on the shelf -> status mismatch
                       "LRC-RF011",                                # reference book shelved in fiction -> misplaced
                       "LRC-XX999",                                # unknown label -> unknown code
                       "LRC-NS041"])                               # scanned twice -> duplicate

    print(f"Seeded {len(BOOKS)} Nicholas Sparks titles, {other_count} titles across the other material types, "
          f"{len(PATRONS)} patrons (PIN 1234), {len(loans) + 1} loans, 2 holds, attendance visits "
          f"and 3 stocktake sessions.")


if __name__ == "__main__":
    sys.exit(main())

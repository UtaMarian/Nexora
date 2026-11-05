"""Builds the Kestrelwind Energy showcase site through NEXORA's own API.

    NEXORA_URL=https://localhost:8443 NEXORA_ADMIN_PASSWORD=... SHOWCASE_PASSWORD=... \
        python seed.py <images folder> <documents folder> [--replace]

Run it from deploy/. --replace deletes an earlier showcase site, with its subsites and the Foundation site, first. The last step moves dates into the past with
SQL through `docker compose exec db`, so the site does not look as if everything happened today.

Kestrelwind Energy and all its people are invented.
"""
import os
import subprocess
from datetime import date, timedelta
import sys
from pathlib import Path

import requests
import urllib3

urllib3.disable_warnings()
BASE = os.environ.get("NEXORA_URL", "https://localhost:8443") + "/api/v1"
ADMIN_PW = os.environ["NEXORA_ADMIN_PASSWORD"]
PW = os.environ["SHOWCASE_PASSWORD"]
IMAGES, FILES = Path(sys.argv[1]), Path(sys.argv[2])
SLUG = "kestrelwind"
ROOT = "/sites/" + SLUG
FOUNDATION = "/sites/kestrelwind-foundation"   # joins the Kestrelwind hub

PEOPLE = {
    "elena.marin": "Elena Marin", "marco.rossi": "Marco Rossi", "priya.nair": "Priya Nair", "daniel.okafor": "Daniel Okafor",
    "sofia.lindqvist": "Sofia Lindqvist", "ana.ionescu": "Ana Ionescu", "liam.walsh": "Liam Walsh", "yuki.tanaka": "Yuki Tanaka",
    "noah.fischer": "Noah Fischer",
}
ELENA, MARCO, PRIYA, DANIEL, SOFIA, ANA, LIAM, YUKI, NOAH = PEOPLE


class Api:
    def __init__(self, login, password):
        self.s = requests.Session()
        self.s.auth = (login, password)
        self.s.verify = False

    def call(self, method, path, ok=(200, 201, 202, 204), **kw):
        r = self.s.request(method, BASE + path, **kw)
        if r.status_code not in ok:
            raise SystemExit(f"{method} {path} -> {r.status_code}: {r.text[:400]}")
        return r.json() if r.content else None

    def get(self, path, **kw): return self.call("GET", path, **kw)
    def post(self, path, body=None, **kw): return self.call("POST", path, json=body, **kw)
    def put(self, path, body=None, **kw): return self.call("PUT", path, json=body, **kw)
    def patch(self, path, body): return self.call("PATCH", path, json=body)
    def delete(self, path, **kw): return self.call("DELETE", path, **kw)

    def upload(self, parent, path, comment=None, overwrite=False, name=None):
        data = {"overwrite": str(overwrite).lower()}
        if comment:
            data["comment"] = comment
        with open(path, "rb") as f:
            r = self.call("POST", f"/nodes/{parent}/files", data=data, files={"file": (name or path.name, f)})
        return r[0] if isinstance(r, list) else r


admin = Api("admin", ADMIN_PW)
who = {login: Api(login, PW) for login in PEOPLE}


def columns(node):
    return {c["displayName"]: c["internalName"] for c in admin.get(f"/nodes/{node}/columns")}


def add_columns(node, specs):
    for spec in specs:
        name, type_, *choices = spec
        admin.post(f"/nodes/{node}/columns", {"displayName": name, "type": type_, "required": False, "choices": choices or None})
    return columns(node)


def items(login, list_id, rows):
    names = columns(list_id)
    for title, values in rows:
        who[login].post(f"/nodes/{list_id}/items", {"title": title, "values": {names[k]: v for k, v in values.items()}})


# ---- page building ---------------------------------------------------------------------------

_part = [0]


def part(type_, **props):
    _part[0] += 1
    return {"id": f"{type_}-{_part[0]}", "type": type_, "props": props}


def text(html): return part("text", html=html)
def image(file_id, alt, caption="", size="full"): return part("image", fileId=file_id, alt=alt, caption=caption, size=size)
def documents(lib, title, limit=6): return part("documents", libraryId=lib, title=title, limit=limit)
def lst(list_id, title, limit=6): return part("list", listId=list_id, title=title, limit=limit)
def news(title="Latest news", limit=4, layout="cards", scope="site"): return part("news", title=title, limit=limit, layout=layout, scope=scope)
def quick_links(title, links, layout="tiles"):
    return part("quicklinks", title=title, layout=layout, links=[{"label": l, "url": u, "description": d} for l, u, d in links])
def events_part(list_id, title, column, limit=5): return part("events", listId=list_id, title=title, dateColumn=column, limit=limit)
def people_part(title, rows): return part("people", title=title, people=[{"login": l, "displayName": PEOPLE[l], "role": r} for l, r in rows])
def embed(url, title, height=380): return part("embed", url=url, title=title, height=height)
def section(layout, *cols, background="none"): return {"layout": layout, "background": background, "columns": [list(c) for c in cols]}


def publish(login, site, title, kind, description, banner, sections):
    api = who[login]
    created = api.post(f"/sites/{site}/pages", {"title": title, "kind": kind})
    pid = created["page"]["id"]
    api.put(f"/pages/{pid}/draft", {"title": title, "description": description, "bannerImageId": banner,
                                    "canvas": {"sections": sections}, "revision": created["revision"]})
    api.post(f"/pages/{pid}/publish")
    return pid


def revise(login, pid, sections):
    api = who[login]
    d = api.get(f"/pages/{pid}")
    c = d["published"]
    api.put(f"/pages/{pid}/draft", {"title": c["title"], "description": c.get("description"), "bannerImageId": c.get("bannerImageId"),
                                    "canvas": {"sections": sections}, "revision": d["revision"]})


def release_checkouts(node):
    """A site with files checked out cannot be deleted."""
    for child in admin.get(f"/nodes/{node}/children"):
        if child["kind"] == "FILE":
            admin.post(f"/nodes/{child['id']}/discard-checkout", ok=(200, 204, 409))
        elif child["kind"] in ("SITE", "LIBRARY", "FOLDER"):
            release_checkouts(child["id"])


def main():
    # people
    existing = {u["login"] for u in admin.get("/admin/users")}
    for login, name in PEOPLE.items():
        if login not in existing:
            admin.post("/admin/users", {"login": login, "displayName": name, "email": f"{login}@kestrelwind.example", "password": PW})

    # site
    for site in admin.get("/admin/sites"):
        if site["path"] in (ROOT, FOUNDATION):   # only what this script made; subsites go with their parent
            if "--replace" not in sys.argv:
                raise SystemExit("The showcase site already exists. Use --replace to build it again.")
            release_checkouts(site["id"])
            admin.delete(f"/admin/sites/{site['id']}")
            admin.delete(f"/recycle-bin/{site['id']}")
    site = admin.post("/admin/sites", {"url": SLUG, "title": "Kestrelwind Energy", "owner": ELENA, "template": "INTRANET",
                                       "description": "Clean power for 1.9 million homes. The Kestrelwind intranet: news, people, projects and everything you need to do your job."})["id"]

    groups = {g["login"].rsplit("-", 1)[1]: g["id"] for g in admin.get(f"/sites/{site}/groups")}
    ids = {u["login"]: u["id"] for u in admin.get("/admin/users") if u["login"] in PEOPLE}
    for login in (ANA, DANIEL):
        admin.post(f"/principals/{groups['owners']}/members", {"principalId": ids[login]})
    for login in (MARCO, PRIYA, SOFIA, LIAM, YUKI):
        admin.post(f"/principals/{groups['members']}/members", {"principalId": ids[login]})
    admin.post(f"/principals/{groups['visitors']}/members", {"principalId": ids[NOAH]})

    # libraries
    libs = {l["name"]: l["id"] for l in admin.get(f"/sites/{site}/libraries")}
    policies, templates, shared = libs["Policies"], libs["Templates"], libs["Shared Documents"]
    projects = admin.post(f"/sites/{site}/libraries", {"name": "Projects", "title": "Project documents", "description": "Charters, plans and reports for every live project"})["id"]
    brand = admin.post(f"/sites/{site}/libraries", {"name": "Brand", "title": "Brand and media", "description": "Approved images and logos"})["id"]
    admin.put(f"/nodes/{policies}/settings", {"versioningEnabled": True, "minorVersionsEnabled": True, "requireCheckout": False, "maxVersions": 50})
    admin.put(f"/nodes/{templates}/settings", {"versioningEnabled": True, "minorVersionsEnabled": False, "requireCheckout": True, "maxVersions": 20})

    pcol = add_columns(policies, [("Document type", "CHOICE", "Policy", "Procedure", "Guideline"), ("Policy owner", "PERSON"), ("Next review", "DATE"), ("Mandatory reading", "BOOLEAN")])
    jcol = add_columns(projects, [("Stage", "CHOICE", "Feasibility", "Planning", "Construction", "Commissioning", "Operating"), ("Project lead", "PERSON"),
                                  ("Capacity MW", "NUMBER"), ("First power", "DATE"), ("Board approved", "BOOLEAN"), ("Project page", "URL")])

    # policies: drafts, then published versions, with real authors and metadata
    policy_rows = [("Code of Conduct.docx", ANA, "Policy", PRIYA, "2027-03-01", True), ("Health and Safety Policy.docx", SOFIA, "Policy", SOFIA, "2027-01-15", True),
                   ("Information Security Policy.docx", DANIEL, "Policy", DANIEL, "2027-02-01", True), ("Flexible Working Policy.docx", PRIYA, "Guideline", PRIYA, "2027-06-30", False),
                   ("Travel and Expenses Policy.docx", MARCO, "Policy", MARCO, "2027-04-01", False), ("Whistleblowing Procedure.docx", ANA, "Procedure", ANA, "2027-05-15", True)]
    files = {}
    for name, login, kind, owner, review, must in policy_rows:
        f = who[login].upload(policies, FILES / "policies" / name, "First draft for review")
        files[name] = f["id"]
        who[login].upload(policies, FILES / "policies" / name, "Comments from Legal included", overwrite=True)
        who[login].post(f"/nodes/{f['id']}/publish", {"comment": "Approved by the executive team"})
        who[login].patch(f"/nodes/{f['id']}", {"title": name.rsplit(".", 1)[0], "metadata": {pcol["Document type"]: kind, pcol["Policy owner"]: owner, pcol["Next review"]: review, pcol["Mandatory reading"]: must}})
    handbook = who[PRIYA].upload(policies, FILES / "pdf" / "Employee Handbook 2026.pdf", "2026 edition")
    who[PRIYA].post(f"/nodes/{handbook['id']}/publish", {"comment": "Published for all staff"})
    who[PRIYA].patch(f"/nodes/{handbook['id']}", {"title": "Employee Handbook 2026", "metadata": {pcol["Document type"]: "Guideline", pcol["Policy owner"]: PRIYA, pcol["Next review"]: "2026-12-15", pcol["Mandatory reading"]: True}})
    who[SOFIA].upload(policies, FILES / "policies" / "Health and Safety Policy.docx", "Draft: new lightning distance rule", overwrite=True)

    # templates need check-out first once they exist
    for path in sorted((FILES / "templates").iterdir()):
        who[ANA].upload(templates, path, "Current template")

    # documents, with folders
    folders = {}
    for name in ("Finance", "Operations", "Strategy", "Board papers"):
        folders[name] = who[ELENA].post(f"/nodes/{shared}/folders", {"name": name})["id"]
        for path in sorted((FILES / "documents" / name).iterdir()):
            who[MARCO if name in ("Finance", "Board papers") else ELENA].upload(folders[name], path, "Issued")
    who[ELENA].upload(folders["Strategy"], FILES / "pdf" / "Annual Report 2025.pdf", "Final, as published")
    who[LIAM].upload(folders["Operations"], FILES / "pdf" / "Blade Inspection Certificate T-117.pdf", "Scanned from the inspector's paper copy")

    # the board papers are private: nobody else can open them or find them
    who[ELENA].post(f"/nodes/{folders['Board papers']}/permissions/break-inheritance", {"copyParentPermissions": False}, ok=(200,))
    for login, level in ((MARCO, "EDIT"),):
        admin.put(f"/nodes/{folders['Board papers']}/permissions/{ids[login]}", {"level": level})
    admin.put(f"/nodes/{folders['Board papers']}/permissions/{ids[ELENA]}", {"level": "FULL_CONTROL"})

    # projects
    rows = [("Northern Reach Offshore", LIAM, "Construction", 1500, "2028-09-01", True), ("Sunfield Hybrid Park", YUKI, "Planning", 440, "2028-04-01", True),
            ("Highmoor Repowering", LIAM, "Planning", 210, "2028-11-01", False), ("GridLink Battery Fleet", DANIEL, "Commissioning", 120, "2027-02-01", True)]
    for name, lead, stage, mw, power, ok in rows:
        folder = who[LIAM].post(f"/nodes/{projects}/folders", {"name": name})["id"]
        f = who[LIAM].upload(folder, FILES / "projects" / f"{name} Project Charter.docx", "Baseline charter")
        who[LIAM].patch(f"/nodes/{f['id']}", {"metadata": {jcol["Stage"]: stage, jcol["Project lead"]: lead, jcol["Capacity MW"]: mw, jcol["First power"]: power, jcol["Board approved"]: ok, jcol["Project page"]: f"{ROOT}/SitePages/our-assets.aspx"}})
        files[name] = f["id"]
        who[LIAM].upload(folder, FILES / "projects" / f"{name} Project Charter.docx", "Updated after stage gate review", overwrite=True)
    who[LIAM].post(f"/nodes/{files['Highmoor Repowering']}/checkout")

    # brand and media
    for name in ("photo-ridge.jpg", "photo-sea.jpg", "photo-solar.jpg", "photo-storage.jpg", "logo.png"):
        who[ANA].upload(brand, IMAGES / name, "Approved for external use")

    # lists
    all_lists = {l["name"]: l["id"] for l in admin.get(f"/sites/{site}/lists")}
    announcements, events, quick, contacts = (all_lists[k] for k in ("Announcements", "Events", "QuickLinks", "Contacts"))
    items(ANA, announcements, [("Pay review letters arrive in January", {"Body": "Everyone will receive a letter from their manager by 22 January.", "Expires": "2027-01-31"}),
                               ("Winter climbing rules start on 1 December", {"Body": "Check the updated cold weather limits before any tower work.", "Expires": "2027-03-01"})])
    items(ANA, events, reversed([("All-hands town hall", {"Start time": "2026-11-12", "End time": "2026-11-12", "Location": "Hamburg HQ and online", "Category": "Business", "Description": "Q3 results and your questions for Elena."}),
                        ("GWO refresher, north region", {"Start time": "2026-11-18", "End time": "2026-11-20", "Location": "Rotterdam training centre", "Category": "Work hours", "Description": "Working at heights and first aid."}),
                        ("Northern Reach site visit", {"Start time": "2026-11-26", "Location": "Port of Esbjerg", "Category": "Meeting", "Description": "Places for 20 colleagues. Ask Liam."}),
                        ("Volunteer day: river clean-up", {"Start time": "2026-12-05", "Location": "Highmoor", "Category": "Get-together", "Description": "Use one of your two volunteering days."}),
                        ("Winter party", {"Start time": "2026-12-11", "Location": "Hamburg HQ", "Category": "Get-together", "Description": "Food, music and the annual safety awards."}),
                        ("Budget sign-off", {"Start time": "2026-12-16", "Location": "Boardroom", "Category": "Business", "Description": "FY27 budget goes to the board."})]))
    items(ANA, quick, [("Book a pool car", {"URL": "https://example.com/pool-cars", "Notes": "Electric first"}), ("Payslips and benefits", {"URL": "https://example.com/hr", "Notes": "Single sign-on"}),
                       ("IT help", {"URL": f"{ROOT}/ITHelp", "Notes": "Log a ticket"}), ("Report a near miss", {"URL": f"{ROOT}/SitePages/safety-first.aspx", "Notes": "Within 24 hours"})])
    for login, role, phone in ((ELENA, "Chief Executive", "+49 40 5550 1001"), (MARCO, "Chief Financial Officer", "+49 40 5550 1002"), (PRIYA, "Head of People", "+49 40 5550 1010"),
                               (DANIEL, "Head of Digital and IT", "+49 40 5550 1020"), (SOFIA, "Head of Health and Safety", "+49 40 5550 1030"), (ANA, "Head of Communications", "+49 40 5550 1040"),
                               (LIAM, "Major Projects Director", "+49 40 5550 1050"), (YUKI, "Solar Development Lead", "+49 40 5550 1060")):
        items(ANA, contacts, [(PEOPLE[login], {"Job title": role, "Email": f"{login}@kestrelwind.example", "Business phone": phone})])
    for column in admin.get(f"/nodes/{contacts}/columns"):
        if column["displayName"] in ("First name", "Company", "Mobile phone"):
            admin.delete(f"/nodes/{contacts}/columns/{column['id']}")

    tasks = who[LIAM].post(f"/sites/{site}/lists", {"name": "Tasks", "title": "Northern Reach tasks", "template": "TASKS", "description": "The live task board for the offshore project"})["id"]
    items(LIAM, tasks, [("Install monopile 61", {"Status": "In progress", "Priority": "(1) High", "Assigned to": LIAM, "Due date": "2026-11-20", "% complete": 70}),
                        ("Approve cable route survey", {"Status": "Waiting on someone else", "Priority": "(1) High", "Assigned to": YUKI, "Due date": "2026-11-14", "% complete": 90}),
                        ("Crew transfer vessel contract", {"Status": "Completed", "Priority": "(2) Normal", "Assigned to": MARCO, "Due date": "2026-10-30", "% complete": 100}),
                        ("Seabird monitoring report", {"Status": "Not started", "Priority": "(2) Normal", "Assigned to": SOFIA, "Due date": "2026-12-01", "% complete": 0}),
                        ("Update port safety plan", {"Status": "In progress", "Priority": "(1) High", "Assigned to": SOFIA, "Due date": "2026-11-28", "% complete": 40})])
    issues = who[DANIEL].post(f"/sites/{site}/lists", {"name": "ITHelp", "title": "IT help desk", "template": "ISSUES", "description": "Report a problem and follow it to resolution"})["id"]
    items(DANIEL, issues, [("SCADA dashboard slow at Highmoor", {"Issue status": "Active", "Priority": "(1) High", "Assigned to": DANIEL, "Due date": "2026-11-10"}),
                           ("New starter laptops for January", {"Issue status": "Active", "Priority": "(2) Normal", "Assigned to": DANIEL, "Due date": "2026-12-18"}),
                           ("Meeting room screen in Rotterdam", {"Issue status": "Resolved", "Priority": "(3) Low", "Assigned to": DANIEL})])
    register = who[DANIEL].post(f"/sites/{site}/lists", {"name": "Sites", "title": "Generation sites", "template": "CUSTOM", "description": "Every site we operate"})["id"]
    rc = add_columns(register, [("Technology", "CHOICE", "Onshore wind", "Offshore wind", "Solar", "Battery"), ("Capacity MW", "NUMBER"), ("Country", "TEXT"), ("Operating since", "DATE"),
                                ("Site manager", "PERSON"), ("Live dashboard", "URL"), ("Grid ready", "BOOLEAN"), ("Notes", "NOTE")])
    items(DANIEL, register, [("Highmoor Ridge", {"Technology": "Onshore wind", "Capacity MW": 126, "Country": "United Kingdom", "Operating since": "2009-05-01", "Site manager": LIAM, "Grid ready": True, "Notes": "Repowering planned for 2028"}),
                             ("Brisa del Sur", {"Technology": "Onshore wind", "Capacity MW": 240, "Country": "Spain", "Operating since": "2016-03-15", "Site manager": YUKI, "Grid ready": True}),
                             ("Doggerfeld One", {"Technology": "Offshore wind", "Capacity MW": 864, "Country": "Germany", "Operating since": "2021-10-01", "Site manager": LIAM, "Grid ready": True}),
                             ("Soleira", {"Technology": "Solar", "Capacity MW": 310, "Country": "Portugal", "Operating since": "2022-06-20", "Site manager": YUKI, "Grid ready": True}),
                             ("Valea Vântului", {"Technology": "Onshore wind", "Capacity MW": 180, "Country": "Romania", "Operating since": "2019-09-01", "Site manager": LIAM, "Grid ready": True, "Notes": "Best availability in the fleet in 2026"}),
                             ("Ostwall Battery", {"Technology": "Battery", "Capacity MW": 60, "Country": "Germany", "Operating since": "2024-02-01", "Site manager": DANIEL, "Grid ready": True})])

    # saved views, a lookup, attachments and item history
    ecol, tcol = columns(events), columns(tasks)
    view = lambda list_id, name, type_, **more: admin.post(f"/nodes/{list_id}/views", {"name": name, "type": type_, "columns": [], "filters": [], **more})
    view(events, "Calendar", "calendar", dateColumn=ecol["Start time"], endColumn=ecol["End time"], isDefault=True)
    view(tasks, "Open work", "table", columns=["Title", tcol["Status"], tcol["Priority"], tcol["Assigned to"], tcol["Due date"]],
         sortBy=tcol["Due date"], filters=[{"column": tcol["Status"], "operator": "ne", "value": "Completed"}], isDefault=True)
    view(tasks, "Due dates", "calendar", dateColumn=tcol["Due date"])
    view(register, "Gallery", "gallery", columns=[rc["Technology"], rc["Capacity MW"], rc["Country"]], isDefault=True)
    view(register, "Offshore and wind", "table", filters=[{"column": rc["Technology"], "operator": "contains", "value": "wind"}], sortBy=rc["Capacity MW"], sortDescending=True)

    photos = {"Highmoor Ridge": "photo-ridge.jpg", "Doggerfeld One": "photo-sea.jpg", "Soleira": "photo-solar.jpg", "Ostwall Battery": "photo-storage.jpg",
              "Brisa del Sur": "news-dawn.jpg", "Valea Vântului": "news-community.jpg"}
    rows_by_title = {n["title"]: n["id"] for n in admin.get(f"/nodes/{register}/children")}
    for title, photo in photos.items():
        who[DANIEL].upload(rows_by_title[title], IMAGES / photo, "Site photo")

    site_column = admin.post(f"/nodes/{tasks}/columns", {"displayName": "Site", "type": "LOOKUP", "lookupListId": register})["internalName"]
    doggerfeld = next(n for n in admin.get(f"/nodes/{register}/children") if n["title"] == "Doggerfeld One")["name"]
    for task in admin.get(f"/nodes/{tasks}/children"):
        who[LIAM].patch(f"/nodes/{task['id']}", {"metadata": {site_column: doggerfeld}})
    who[LIAM].upload(admin.get(f"/nodes/{tasks}/children")[0]["id"], FILES / "pdf" / "Blade Inspection Certificate T-117.pdf", "Evidence for the stage gate")

    # images for pages
    def pic(name):
        with open(IMAGES / name, "rb") as f:
            return admin.call("POST", f"/sites/{site}/page-images", files={"file": (name, f)})["id"]

    pics = {p.name: pic(p.name) for p in sorted(IMAGES.glob("*.jpg"))}

    # news, oldest first
    N = [(ANA, "Northern Reach passes the halfway mark", "Sixty monopiles are in the seabed, ahead of plan and without a single lost-time injury.", "news-offshore.jpg",
          "<h2>A record autumn offshore</h2><p>The installation vessel set its sixtieth foundation last week, three weeks ahead of the baseline plan. When it is finished, Northern Reach will power <strong>1.2 million homes</strong>.</p><blockquote>This is the safest large offshore build I have worked on, and that is down to the crews.</blockquote><p>Liam Walsh, Major Projects Director</p><h3>What happens next</h3><ul><li>Array cable installation starts in March</li><li>The offshore substation sails in June</li><li>First power in the third quarter of 2028</li></ul>"),
         (SOFIA, "212 days without a lost-time injury", "Our longest run yet. Here is what the regions did differently.", "news-safety.jpg",
          "<h2>Thank you</h2><p>Reporting near misses went up 40 percent this year, and injuries came down. The two are connected: every report teaches us something.</p><p>Read the updated <a href=\"" + ROOT + "/SitePages/safety-first.aspx\">safety pages</a> before the winter climbing season.</p>"),
         (YUKI, "Sunfield Hybrid Park wins planning approval", "320 MW of solar and 120 MW of batteries on former quarry land.", "news-solar.jpg",
          "<h2>From quarry to clean power</h2><p>The county approved Sunfield unanimously after two years of work with neighbours. The park will restore 40 hectares of heathland alongside the panels.</p><ul><li><strong>40 percent</strong> of the workforce will be local</li><li>Construction starts in spring 2027</li></ul>"),
         (DANIEL, "Meet NEXORA, our new home for documents and news", "Everything now lives in one place, on our own servers.", "news-digital.jpg",
          "<h2>One place to find everything</h2><p>Search now looks inside every document, even scanned certificates. Try it: type <strong>leading edge erosion</strong> in the search box.</p><p>You will only ever see what you have permission to see.</p>"),
         (ANA, "Community fund passes EUR 11 million", "From school roofs to river clean-ups, what our neighbours chose this year.", "news-community.jpg",
          "<h2>Decided locally</h2><p>Every site has a panel of residents who choose where the money goes. This year it paid for 14 school solar roofs, 3 sports halls and a mobile library in the Highmoor valley.</p>"),
         (DANIEL, "Ostwall battery answers its first grid call in 140 ms", "A quiet milestone with a big future.", "news-storage.jpg",
          "<h2>Fast enough to matter</h2><p>When a generator tripped offline on Tuesday, Ostwall was delivering full power in 140 milliseconds, faster than any turbine could react.</p>"),
         (PRIYA, "Graduate programme doubles to 24 places", "Applications open on 1 December, and referrals are welcome.", "news-team.jpg",
          "<h2>Help us find them</h2><p>Know someone who should be building the energy system of the future? Rotations cover engineering, data, finance and community work.</p>")]
    news_ids = [publish(login, site, title, "NEWS", desc, pics[cover], [section("one", [text(body.split("</h2>", 1)[1])])]) for login, title, desc, cover, body in N]

    # pages
    page = lambda n: f"{ROOT}/SitePages/{n}.aspx"
    about = publish(ANA, site, "About us", "PAGE", "Who we are and why we do it", pics["banner-about.jpg"], [
        section("oneThirdRight", [text("<h2>Our purpose</h2><p>We build and run the clean power stations that let communities leave fossil fuels behind, and we leave every place better than we found it.</p><p>Kestrelwind started in 2008 with six turbines on a ridge in Highmoor. Today, 1,180 colleagues run 31 sites in seven countries.</p><h3>What we believe</h3><ul><li><strong>Everybody home safe.</strong> Nothing is more urgent.</li><li><strong>Good neighbours.</strong> We ask first and we listen.</li><li><strong>Built to last.</strong> Thirty-year assets need thirty-year thinking.</li></ul>")],
                [image(pics["photo-ridge.jpg"], "Wind turbines on a ridge at dawn", "Highmoor Ridge, where it all began"), lst(contacts, "Leadership team", 4)]),
        section("three", [text("<h2>2008</h2><p>First six turbines at Highmoor</p>")], [text("<h2>2021</h2><p>Doggerfeld One, our first offshore wind farm</p>")], [text("<h2>2028</h2><p>Northern Reach: 1.5 GW of first power</p>")], background="strong"),
        section("oneThirdLeft", [people_part("Leadership", [(ELENA, "Chief Executive"), (MARCO, "Chief Operating Officer"), (PRIYA, "People and Culture"), (DANIEL, "Digital and IT")])],
                [embed("https://www.google.com/maps?q=Hamburg&output=embed", "Our head office in Hamburg")])])
    assets = publish(LIAM, site, "Our assets", "PAGE", "Thirty-one sites, seven countries, one control room", pics["banner-assets.jpg"], [
        section("one", [lst(register, "Generation sites", 6)]),
        section("two", [image(pics["photo-sea.jpg"], "Offshore turbines at dusk", "Doggerfeld One at dusk")], [image(pics["photo-storage.jpg"], "Battery containers beneath wind turbines at night", "Ostwall battery site")]),
        section("oneThirdLeft", [text("<h2>Major projects</h2><p>Every project keeps its charter, plans and reports in one library, tagged with its stage, lead and capacity.</p>")], [documents(projects, "Latest project documents", 5)], background="soft"),
        section("one", [lst(tasks, "Northern Reach tasks", 5)])])
    safety = publish(SOFIA, site, "Safety first", "PAGE", "Everybody home safe, every day", pics["banner-safety.jpg"], [
        section("three", [text("<h2>212</h2><p>days since the last lost-time injury</p>")], [text("<h2>0.41</h2><p>lost-time injury rate, target below 0.5</p>")], [text("<h2>1,364</h2><p>near misses reported this year</p>")], background="strong"),
        section("oneThirdRight", [text("<h2>Stop work authority</h2><p>Anyone can stop any job at any time, and nobody will ever be criticised for doing so.</p><h3>Before you climb</h3><ol><li>Check your GWO certificate is current</li><li>Inspect your harness</li><li>Agree the rescue plan</li><li>Check wind speed and lightning alerts</li></ol>")], [documents(policies, "Safety documents", 4)])])
    people = publish(PRIYA, site, "People and culture", "PAGE", "Everything about working here", pics["banner-people.jpg"], [
        section("two", [text("<h2>New here?</h2><p>Welcome. Start with the <a href=\"" + ROOT + "/Policies\">Employee Handbook</a>, then book your site visit in your first month. Everyone sees a turbine up close, whatever their job.</p>")],
                [text("<h2>What we offer</h2><ul><li>28 days of leave, plus 2 volunteering days</li><li>26 weeks of parental leave on full pay</li><li>EUR 1,500 a year for learning</li></ul>")]),
        section("one", [lst(events, "What is on", 6)], background="soft"), section("one", [lst(contacts, "Who to ask", 8)])])
    digital = publish(DANIEL, site, "Digital and IT", "PAGE", "Help, guides and how we keep the grid secure", pics["banner-digital.jpg"], [
        section("oneThirdRight", [lst(issues, "IT help desk", 5)], [text("<h2>Need help?</h2><p>Log a ticket in the <a href=\"" + ROOT + "/ITHelp\">IT help desk</a>, or call +49 40 5550 1020.</p><h3>Stay safe</h3><p>Use the Report button on any suspicious email.</p>")])])
    sustain = publish(ELENA, site, "Sustainability", "PAGE", "Our 2030 commitments", pics["banner-sustainability.jpg"], [
        section("three", [text("<h2>4.1 Mt</h2><p>CO2 avoided every year</p>")], [text("<h2>94%</h2><p>of old blades recycled or reused</p>")], [text("<h2>31</h2><p>sites with a biodiversity plan</p>")], background="strong"),
        section("two", [text("<h2>Nature first</h2><p>Every site has a biodiversity plan with measurable goals. At Sunfield we are restoring 40 hectares of heathland.</p>")], [image(pics["photo-solar.jpg"], "Rows of solar panels under a bright sky", "Soleira solar park, Portugal")])])

    publish(ANA, site, "News", "PAGE", "Stories from across Kestrelwind", pics["banner-sustainability.jpg"], [section("one", [news("All news", 12)])])

    home = publish(ANA, site, "Welcome to Kestrelwind", "PAGE", "Clean power for 1.9 million homes, and everything you need to do your job.", pics["banner-home.jpg"], [
        section("three", [text("<h2>2.4 GW</h2><p>of clean power operating across 31 sites</p>")], [text("<h2>1,180</h2><p>colleagues in seven countries</p>")], [text("<h2>212 days</h2><p>without a lost-time injury</p>")], background="strong"),
        section("oneThirdRight", [news("Latest news", 3, scope="hub")], [events_part(events, "Coming up", ecol["Start time"]),
            quick_links("Quick links", [("Report a near miss", page("safety-first"), "Takes two minutes"), ("Forms and templates", f"{ROOT}/Templates", "Everything you fill in"),
                                        ("Get IT help", f"{ROOT}/ITHelp", "Log a ticket"), ("Operations", f"{ROOT}/operations", "The team site")], layout="list")]),
        section("two", [image(pics["photo-sea.jpg"], "Offshore wind turbines at sunset", "Northern Reach will power 1.2 million homes")],
                [text("<h2>Strategy 2030</h2><p><strong>Twice the clean power, half the wait.</strong> We are heading for 5 GW by 2030, with every wind farm paired with battery storage.</p><blockquote>The energy transition will be won by the people who show up, year after year, in all weathers.</blockquote><p>Elena Marin, Chief Executive</p><p><a href=\"" + ROOT + "/SitePages/sustainability.aspx\">See our commitments</a></p>")], background="soft"),
        section("three", [documents(policies, "Policies and procedures", 5)], [lst(tasks, "Northern Reach tasks", 4)], [lst(register, "Generation sites", 5)])])
    who[ELENA].put(f"/sites/{site}/home-page", {"pageId": home})

    # a page with history, and one with changes waiting to be published
    revise(ANA, about, [section("one", [text("<h2>Our purpose</h2><p>We build and run the clean power stations that let communities leave fossil fuels behind.</p>")])])
    who[ANA].post(f"/pages/{about}/discard")
    revise(SOFIA, safety, [section("one", [text("<h2>Draft: winter climbing rules</h2><p>Not published yet.</p>")])])

    # look and feel
    with open(IMAGES / "logo.png", "rb") as f:
        admin.call("PUT", f"/sites/{site}/logo", files={"file": ("logo.png", f, "image/png")})
    admin.put(f"/sites/{site}/design", {
        "theme": "teal", "header": {"layout": "extended", "background": "strong", "showTitle": True, "showLogo": True},
        "navigation": [{"label": "Home", "url": ROOT}, {"label": "News", "url": page("news")}, {"label": "About us", "url": page("about-us")}, {"label": "Our assets", "url": page("our-assets")},
                       {"label": "Sustainability", "url": page("sustainability")},
                       {"label": "Working here", "groups": [
                           {"heading": "Your work", "links": [{"label": "Safety first", "url": page("safety-first")}, {"label": "People and culture", "url": page("people-and-culture")}, {"label": "Digital and IT", "url": page("digital-and-it")}]},
                           {"heading": "Documents", "links": [{"label": "Policies", "url": f"{ROOT}/Policies"}, {"label": "Templates", "url": f"{ROOT}/Templates"}, {"label": "All documents", "url": f"{ROOT}/Shared Documents"}]}]}],
        "home": {"welcomeTitle": "Welcome to Kestrelwind", "welcome": "", "quickLinks": [], "showNews": False, "showLibraries": True, "showLists": True}})

    intranet(site, page)

    # sharing and alerts
    who[ELENA].post(f"/nodes/{files['Code of Conduct.docx']}/share-people", {"principals": [ids[NOAH]], "level": "READ", "message": "Please read this before your first site visit."})
    who[PRIYA].put(f"/nodes/{policies}/alert", {"frequency": "DAILY"})
    who[YUKI].put(f"/nodes/{projects}/alert", {"frequency": "IMMEDIATE"})

    # rules, then approvals and a review going through them
    rule = lambda container, name, definition: admin.post(f"/nodes/{container}/rules", {"name": name, "definition": definition})
    person = lambda login: {"kind": "PERSON", "login": login, "displayName": PEOPLE[login]}
    rule(projects, "New project documents need approval", {"trigger": "CREATED", "conditions": [{"column": "FileType", "operator": "eq", "value": "docx"}],
         "actions": [{"type": "REQUEST_APPROVAL", "people": [person(MARCO)], "mode": "ALL", "dueDays": 5}]})
    rule(projects, "File what was approved", {"trigger": "APPROVED", "conditions": [], "actions": [{"type": "MOVE_TO_FOLDER", "folder": "Approved"}]})
    rule(policies, "Remind owners before a policy review", {"trigger": "DATE", "dateColumn": pcol["Next review"], "daysBefore": 30, "conditions": [],
         "actions": [{"type": "SEND_EMAIL", "people": [{"kind": "COLUMN", "column": pcol["Policy owner"]}], "subject": "{title} is due for review", "body": "It is due in 30 days: {link}"}]})
    rule(issues, "High priority tickets alert IT", {"trigger": "CREATED", "conditions": [{"column": columns(issues)["Priority"], "operator": "eq", "value": "(1) High"}],
         "actions": [{"type": "SEND_EMAIL", "people": [person(DANIEL)], "subject": "High priority: {title}", "body": "{by} logged it. {link}"}]})
    who[SOFIA].post(f"/nodes/{files['Health and Safety Policy.docx']}/approvals", {"kind": "APPROVAL", "mode": "SEQUENTIAL", "people": [ids[PRIYA], ids[ELENA]], "dueDays": 7,
                    "message": "New lightning distance rule. Please check section 2."})
    who[PRIYA].post(f"/nodes/{files['Flexible Working Policy.docx']}/approvals", {"kind": "REVIEW", "people": [ids[ANA], ids[DANIEL]], "message": "Does this still match how teams work?"})

    # something in the recycle bin
    extra = who[LIAM].upload(folders["Operations"], FILES / "policies" / "Flexible Working Policy.docx", "Uploaded here by mistake")
    who[LIAM].delete(f"/nodes/{extra['id']}")

    backdate(site, [i for i in news_ids])
    admin.post("/admin/search/rebuild")
    print("site", ROOT)


def admin_library(site):
    return next(l["id"] for l in admin.get(f"/sites/{site}/libraries") if l["name"] == "Shared Documents")


def intranet(site, page):
    """A hub, subsites inside the main site, and a separate site that joins the hub."""
    ops_url, north_url = f"{ROOT}/operations", f"{ROOT}/operations/north-sea"
    ids = {u["login"]: u["id"] for u in admin.get("/admin/users") if u["login"] in PEOPLE}

    # Operations has its own people; North Sea shares them; Graduates shares the main site's
    operations = who[ELENA].post(f"/sites/{site}/subsites", {"url": "operations", "title": "Operations", "description": "Keeping 2.4 GW running, safely, every hour of the day.", "template": "TEAM"})["id"]
    groups = {g["login"].rsplit("-", 1)[1]: g["id"] for g in admin.get(f"/sites/{operations}/groups")}
    who[ELENA].post(f"/principals/{groups['owners']}/members", {"principalId": ids[MARCO]})
    for login in (LIAM, SOFIA):
        who[ELENA].post(f"/principals/{groups['members']}/members", {"principalId": ids[login]})
    for login in (ANA, DANIEL, PRIYA, YUKI, NOAH):
        who[ELENA].post(f"/principals/{groups['visitors']}/members", {"principalId": ids[login]})
    north = who[MARCO].post(f"/sites/{operations}/subsites", {"url": "north-sea", "title": "North Sea", "description": "Doggerfeld One and the Northern Reach build.", "template": "TEAM", "uniquePermissions": False})["id"]
    who[ELENA].post(f"/sites/{site}/subsites", {"url": "graduates", "title": "Graduate programme", "description": "Rotations, mentors and how to apply.", "template": "TEAM", "uniquePermissions": False})

    visits = who[MARCO].post(f"/sites/{operations}/lists", {"name": "Site visits", "template": "EVENTS"})["id"]
    day = lambda n: (date.today() + timedelta(days=n)).isoformat()
    date_col = columns(visits)["Start time"]
    for title, n, where in [("Highmoor Ridge blade inspection", 6, "Highmoor Ridge"), ("Doggerfeld One crew change", 9, "Esbjerg harbour"), ("Ostwall battery fire drill", 13, "Ostwall"),
                            ("Sunfield inverter upgrade", 20, "Sunfield")]:
        who[MARCO].post(f"/nodes/{visits}/items", {"title": title, "values": {date_col: day(n), columns(visits)["End time"]: day(n), columns(visits)["Location"]: where}})

    # a department page from a built-in template, its web parts filled in
    created = who[MARCO].post(f"/sites/{operations}/pages", {"title": "Operations home", "kind": "PAGE", "template": "DEPARTMENT"})
    pid = created["page"]["id"]
    canvas = [section("three", [text("<h2>What we do</h2><p>We run every wind farm, solar park and battery we own, from one control room in Hamburg.</p>")],
                      [text("<h2>Who it is for</h2><p>Everyone who works on, or depends on, our sites.</p>")],
                      [text("<h2>Call us</h2><p>The control room answers day and night on +49 40 5550 1000.</p>")], background="strong"),
              section("oneThirdRight", [news("Operations news", 4, layout="list")],
                      [quick_links("Quick links", [("North Sea", north_url, "Doggerfeld One and Northern Reach"), ("Safety first", page("safety-first"), "Stop work authority and more")], layout="list"),
                       events_part(visits, "Site visits", date_col)]),
              section("one", [people_part("The team", [(MARCO, "Chief Operating Officer"), (LIAM, "Asset Manager"), (SOFIA, "Head of Safety")])], background="soft")]
    who[MARCO].put(f"/pages/{pid}/draft", {"title": "Operations home", "description": "Everything about running our sites", "bannerImageId": None,
                                           "canvas": {"sections": canvas}, "revision": created["revision"]})
    who[MARCO].post(f"/pages/{pid}/publish")
    who[MARCO].put(f"/sites/{operations}/home-page", {"pageId": pid})
    who[MARCO].post(f"/pages/{pid}/save-as-template", {"title": "Operations team page"})
    publish(LIAM, operations, "Doggerfeld availability hits 98.2%", "NEWS", "Our best quarter offshore.", None, [section("one", [text("<p>Fewer weather stops and faster crew changes did it.</p>")])])

    # the hub
    admin.put(f"/admin/sites/{site}/hub", {"enabled": True})
    who[ELENA].put(f"/sites/{site}/hub", {"title": "Kestrelwind", "navigation": [
        {"label": "Departments", "groups": [
            {"heading": "On the ground", "links": [{"label": "Operations", "url": ops_url}, {"label": "North Sea", "url": north_url}, {"label": "Safety first", "url": page("safety-first")}]},
            {"heading": "Our people", "links": [{"label": "People and culture", "url": page("people-and-culture")}, {"label": "Graduate programme", "url": f"{ROOT}/graduates"}]},
            {"heading": "In the community", "links": [{"label": "Foundation", "url": FOUNDATION}, {"label": "Sustainability", "url": page("sustainability")}]}]},
        {"label": "News", "url": page("news")}, {"label": "Policies", "url": f"{ROOT}/Policies"}, {"label": "IT help", "url": f"{ROOT}/ITHelp"}]})

    # a separate site that joins it: visitors keep their own site, and gain the hub's bar and look
    foundation = admin.post("/admin/sites", {"url": FOUNDATION.rsplit("/", 1)[1], "title": "Kestrelwind Foundation", "owner": ANA, "template": "TEAM",
                                             "description": "Every project gives back: EUR 2.4 million to neighbours this year."})["id"]
    groups = {g["login"].rsplit("-", 1)[1]: g["id"] for g in admin.get(f"/sites/{foundation}/groups")}
    for login in PEOPLE:
        if login != ANA:
            who[ANA].post(f"/principals/{groups['visitors']}/members", {"principalId": ids[login]})
    who[ANA].put(f"/sites/{foundation}/hub-association", {"hubId": site})
    # the one public site: visitors read it without signing in, and see no hub bar, as they cannot open the hub
    admin.put("/admin/anonymous", {"enabled": True})
    who[ANA].put(f"/nodes/{foundation}/anonymous", {"enabled": True})
    who[ANA].upload(admin_library(foundation), FILES / "policies" / "Code of Conduct.docx", name="Grants code of conduct.docx")
    with open(IMAGES / "news-team.jpg", "rb") as f:
        banner = who[ANA].call("POST", f"/sites/{foundation}/page-images", files={"file": ("solar-classroom.jpg", f, "image/jpeg")})["id"]   # pictures belong to one site
    publish(ANA, foundation, "Highmoor school opens its solar classroom", "NEWS", "Funded by the Foundation, built by the village.", banner,
            [section("one", [text("<p>Forty pupils now read their own roof's output on a screen in the corridor.</p>")])])


def backdate(site, news_ids):
    """News published over the past weeks, newest last; everything else changed at some point this autumn."""
    sql = [f"update site_page set published_at = now() - interval '{(len(news_ids) - i) * 4 - 3} days {i * 97} minutes', modified_at = now() - interval '{(len(news_ids) - i) * 4 - 3} days {i * 97} minutes' where id = '{pid}';"
           for i, pid in enumerate(news_ids)]
    sql.append(f"update site_page set published_at = now() - interval '12 days', modified_at = now() - interval '12 days' where site_id = '{site}' and kind = 'PAGE';")
    sql.append(f"update node set modified_at = now() - (abs(hashtext(id::text)) % 40 + 1) * interval '1 day' - (abs(hashtext(name)) % 600) * interval '1 minute' where site_id = '{site}' and kind in ('FILE','LIST_ITEM','FOLDER');")
    subprocess.run(["docker", "compose", "exec", "-T", "db", "psql", "-q", "-U", "nexora", "-d", "nexora"], input="\n".join(sql).encode(), check=True)


main()

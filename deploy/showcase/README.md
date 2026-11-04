# Showcase site

Builds **Kestrelwind Energy**, a fictional company intranet at `/sites/kestrelwind`, to demonstrate what NEXORA can do.
The company, its people and its numbers are invented. Illustrations are drawn by `art.js`.

It needs a running Docker stack, Python 3.11+, Node, and Google Chrome.

```sh
cd deploy
python -m venv .venv && .venv/bin/pip install python-docx openpyxl python-pptx requests
(cd showcase && npm i --no-save playwright-core)

node showcase/art.js /tmp/showcase/art
.venv/bin/python showcase/documents.py /tmp/showcase/art /tmp/showcase/files
node showcase/pdfs.js /tmp/showcase/files

NEXORA_ADMIN_PASSWORD=... SHOWCASE_PASSWORD=choose-one \
  .venv/bin/python showcase/seed.py /tmp/showcase/art /tmp/showcase/files --replace
```

`--replace` deletes an earlier copy of the site first. Nine local accounts are created with `SHOWCASE_PASSWORD`. They
are demo accounts, so do not run this on a production farm. The last step moves dates into the past with SQL, so the
site does not look as if everything happened today.

| Sign in as | Role | Shows |
| --- | --- | --- |
| `elena.marin` | Site owner, CEO | Everything, including the private Board papers folder |
| `ana.ionescu`, `daniel.okafor` | Site owners | Editing pages, the site's look and permissions |
| `marco.rossi` | Member, CFO | Member with access to Board papers |
| `priya.nair`, `sofia.lindqvist`, `liam.walsh`, `yuki.tanaka` | Members | Everyday work |
| `noah.fischer` | Visitor | Read-only. Board papers do not exist for this account, even in search |

Things to try:

- Search for `leading edge erosion`, which is only inside a scanned PDF, then `Project Falcon` as `noah.fischer` and as `elena.marin`.
- Open `Strategy 2030.pptx` in the browser editor, and as two users at once to co-author.
- See the version history of the Health and Safety Policy, with a 1.1 draft after the published 1.0.
- Sign in as `sofia.lindqvist` to see unpublished changes on the Safety first page.
- Open `Highmoor Repowering Project Charter.docx`, checked out by Liam Walsh.
- Open Events as a calendar, Generation sites as a gallery, and the Open work view of Northern Reach tasks.
- Choose New in Shared Documents and start from a template.
- Share a file with a link, and open it in a private window.
- As `noah.fischer`, open Shared with me.
- See email at http://localhost:8025.
- As `priya.nair`, open Approvals and answer the Health and Safety Policy request, then finish it as `elena.marin`. It publishes as version 2.0.
- Open Automate in Project documents, then upload a Word file as `liam.walsh` and approve it as `marco.rossi`.
- Empty the recycle bin, or restore the file Liam deleted by mistake.

## Intranet navigation

The main site is a hub. Try the **Departments** menu in the dark bar, then **Working here** in the site's own navigation.

- `/sites/kestrelwind/operations` is a subsite with its own groups. Its home page came from the Department home template,
  and it has a saved template, Operations team page.
- `/sites/kestrelwind/operations/north-sea` and `/sites/kestrelwind/graduates` share the people of their parent.
- `/sites/kestrelwind-foundation` is a separate site that joined the hub, so its news appears on the Kestrelwind home page.
- **About us** has a People web part and an embedded map. The map needs internet access.

`--replace` deletes `/sites/kestrelwind`, with its subsites, and `/sites/kestrelwind-foundation`. It touches no other site.

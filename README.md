# NEXORA

A self-hosted document and intranet platform that replaces SharePoint Server for organizations that must stay
on-premises. It runs on Linux containers and keeps what people rely on in SharePoint: sites, libraries, lists, pages,
permissions, Office editing, mapped drives and SharePoint-style URLs.

See the [development plan](SharePoint%20Server%20Replacement%20–%20Development%20Plan.md) for the product strategy.

**Contents:** [Why NEXORA](#why-nexora) · [Features](#features) · [Architecture](#architecture) ·
[Scaling and redundancy](#scaling-and-redundancy) · [Run it](#run-it) · [Not built yet](#not-built-yet) ·
[Repository layout](#repository-layout)

## Why NEXORA

SharePoint Server 2016 and 2019 left extended support on 14 July 2026. NEXORA is a replacement, not a clone: it
copies the concepts people know and leaves out the Windows farm that SharePoint needs underneath.

| | SharePoint Server | NEXORA |
| --- | --- | --- |
| Runs on | Windows Server, IIS, SQL Server | Linux containers, PostgreSQL |
| Application | Many services across server roles | One Spring Boot application, plus open-source services |
| File storage | Inside SQL content databases by default | S3-compatible store or NFS, with identical files stored once |
| Office in the browser | Office Online Server on its own servers | Collabora Online, a container |
| Search | Search service application and topology | OpenSearch and Tika. The index can be rebuilt from the database without repeating OCR |
| Sign-in | Windows and AD integration | Keycloak: AD/LDAP, Kerberos, AD FS, OIDC |
| Install | Farm setup across several servers | `docker compose up` on one host |

These are design differences. **NEXORA has not been load-tested or benchmarked against SharePoint yet**, so there are
no speed or capacity figures to quote.

## Features

Status: ✅ works, ⚠️ works with an important limit.

### Sites and intranet

| Feature | Status |
| --- | --- |
| Team sites with Owners, Members and Visitors groups and a default Documents library | ✅ |
| Site templates: Team, Communication, Project and Intranet portal | ✅ |
| Subsites up to 4 levels, hub sites with shared navigation, theme and news, mega menus. See [docs/intranet.md](docs/intranet.md) | ✅ |
| Change the look: 8 themes, header layout and background, logo, top navigation, home page | ✅ |
| Modern pages and news at `/sites/<site>/SitePages/<name>.aspx`, with drafts, publishing, version history and page templates | ✅ |
| Sections of 1 to 3 columns, with text, image, documents, list, news, quick links, events, people and embed web parts | ✅ |
| SharePoint-style URLs (`/sites/hr/Shared Documents/a.docx`) in the browser and the API | ✅ |
| Anonymous reading of chosen sites, libraries and lists, off by default. See [docs/anonymous-access.md](docs/anonymous-access.md) | ✅ |
| "This site wasn't shared with you" page, and menus trimmed to what each person can open | ✅ |

### Documents

| Feature | Status |
| --- | --- |
| Libraries, folders, multi-file and drag-and-drop upload, download, rename, move | ✅ |
| Major and minor (draft) versions, publish, history, restore, delete, version limits | ✅ |
| Who can see drafts, per library: anyone who can read, or only people who can edit. The read-only browser view is not yet tried against a real Collabora. See [docs/administration.md](docs/administration.md#who-can-see-drafts) | ⚠️ |
| Check-out with a private draft, check-in, discard, and "require check-out" libraries | ✅ |
| New Word, Excel and PowerPoint files, blank or from the site's Templates library | ✅ |
| Custom columns: text, note, number, date, yes/no, choice, hyperlink, person, lookup | ✅ |
| Recycle bin with restore and permanent delete | ✅ |
| Storage on S3-compatible stores or a filesystem/NFS, content-addressed and de-duplicated | ✅ |

### Office

| Feature | Status |
| --- | --- |
| Browser editing of Word, Excel, PowerPoint and OpenDocument files with real-time co-authoring (WOPI and Collabora) | ✅ |
| Desktop Office open and save over WebDAV with locks, and "Open in Word" links | ✅ |
| Map a library as a network drive on Windows, macOS and Linux. Office "safe save" keeps version history | ✅ |

### Lists

| Feature | Status |
| --- | --- |
| Templates: Tasks, Issues, Contacts, Announcements, Links, Events and Custom | ✅ |
| Sortable, filterable lists with item forms, attachments and item version history | ✅ |
| Saved views as a table, calendar or gallery | ✅ |
| Lookup columns to other lists in the same site. See [docs/lists-sharing-alerts.md](docs/lists-sharing-alerts.md) | ✅ |

### Search

| Feature | Status |
| --- | --- |
| Full-text search inside documents, list items, pages and news, with snippets and filters | ✅ |
| OCR for images and scanned PDFs, and query syntax such as `"exact phrase"`, `filetype:pdf` and `author:name` | ✅ |
| Security trimming, checked in the index and again against the database | ✅ |
| Search health, rebuild and an on/off switch for administrators. See [docs/search.md](docs/search.md) | ✅ |

### Collaboration and workflow

| Feature | Status |
| --- | --- |
| Sharing with people, Shared with me, Recent | ✅ |
| Expiring read-only links, for the organization or for anyone, with an optional password | ✅ |
| Email alerts, right away or as daily and weekly summaries, through a retrying outbox. Tested with Mailpit only | ⚠️ |
| Approvals and reviews: everyone, first answer, or one after another. See [docs/workflows.md](docs/workflows.md) | ✅ |
| Rule builder for libraries and lists: when something happens, if conditions hold, then act | ✅ |

### Security and identity

| Feature | Status |
| --- | --- |
| Read, Contribute, Edit and Full Control levels, inheritance, break and reset, nested groups | ✅ |
| Inaccessible items return 404, so the API does not reveal that they exist | ✅ |
| Single sign-on through Keycloak: OIDC with PKCE, AD/LDAP users and nested groups, Kerberos, AD FS (SAML). See [docs/identity.md](docs/identity.md) | ✅ |
| Directory sync, so AD groups work in permissions before first sign-in, and admin group mapping | ✅ |
| Browser sessions with CSRF protection, plus HTTP Basic and bearer tokens for scripts | ✅ |
| HTTPS for the app, API, WebDAV, Keycloak and Collabora through nginx | ✅ |
| Audit log of reads, writes and permission changes, mirrored to the `nexora.audit` logger for a SIEM | ✅ |

### Administration and migration

| Feature | Status |
| --- | --- |
| Central Administration at `/admin`: overview, health, sites, jobs, logs, users, directory, settings. See [docs/administration.md](docs/administration.md) | ✅ |
| Site and farm backup, restore under any URL, site export and import between farms | ✅ |
| Scheduled farm backups, daily or weekly, keeping the newest few | ✅ |
| Storage limits on sites, with a warning email to owners. See [docs/administration.md](docs/administration.md#site-storage-limits) | ✅ |
| SharePoint migration: scan, copy with versions, metadata and permissions, delta sync, old-link redirects. **Tested against a simulated farm only** | ⚠️ |
| REST API at `/api/v1`, OpenAPI spec at `/v3/api-docs`, Swagger UI at `/swagger-ui.html` | ✅ |
| Health probes at `/actuator/health` on the API port | ✅ |

## Architecture

NEXORA is a **modular monolith**: one application, split into Java packages with clear boundaries. All permanent data
sits in PostgreSQL and the blob store. Background work is held in database tables, so a crash or an outage delays it
without losing it.

### One-host stack (what Docker Compose runs)

```
                Browser          Desktop Office / Explorer       Scripts
                   │                        │                       │
                   └────────────────────────┼───────────────────────┘
                                            │ HTTPS :8443
                                            ▼
              ┌───────────────────────────────────────────────────────────┐
              │  web (nginx): TLS, the React web app, routing             │
              └───────┬───────────────────┬────────────────────┬──────────┘
                      │ /auth             │ /api  /dav         │ /browser  /cool
                      ▼                   ▼                    ▼
              ┌──────────────┐  ┌────────────────────┐  ┌──────────────┐
              │  Keycloak    │◀─│  backend           │◀─│  Collabora   │
              │  sign-in     │  │  Spring Boot       │  │  Online      │
              └──────┬───────┘  │  Java 21           │  │  Office      │
                     │          │                    │  │  editor      │
                     ▼          │  REST API, WebDAV, │  └──────────────┘
              ┌──────────────┐  │  WOPI, permissions,│    WOPI: load and
              │ AD/LDAP, AD  │  │  background workers│    save documents
              │ FS, Kerberos │  └──┬───┬───┬───┬───┬─┘
              └──────────────┘     │   │   │   │   │
                 ┌─────────────────┘   │   │   │   └──────────────┐
                 ▼              ┌──────┘   │   └───────┐          ▼
          ┌────────────┐ ┌──────┴─────┐ ┌──┴───────┐ ┌─┴──────┐ ┌────────┐
          │ PostgreSQL │ │ SeaweedFS  │ │OpenSearch│ │ Tika   │ │ SMTP   │
          │ metadata,  │ │ S3 blobs:  │ │ search   │ │ text   │ │ Mailpit│
          │ permissions│ │ file       │ │ index    │ │ reading│ │ in the │
          │ work queues│ │ contents   │ │          │ │ and OCR│ │ demo   │
          └────────────┘ └────────────┘ └──────────┘ └────────┘ └────────┘
            must keep      must keep     rebuildable  no state   external
```

Keycloak has its own PostgreSQL database, left out of the diagram.

### Inside the backend

```
  HTTP      │  api/   dav/   office/ (WOPI)   lists/   pages/   portal/   sharing/   admin/
            │
  Rules     │  acl/ PermissionService ── the only place that decides access
            │  content/ tree, versions, check-out, locks     identity/ users, groups, SSO
            │
  Record    │  audit/ AuditService sees every write, inside the writer's transaction
            │         │
            │         ├──▶ search_queue      ──▶ search/ indexer ──▶ OpenSearch, Tika
            │         ├──▶ alert events      ──▶ alerts/         ──▶ mail/ outbox ──▶ SMTP
            │         └──▶ workflow events   ──▶ workflow/ engine
            │
  Storage   │  storage/ BlobStore (S3 or filesystem)              JPA ──▶ PostgreSQL
```

A change and its follow-up work commit in one transaction, so a rollback leaves no work behind and a crash loses none.
The design decisions are in [docs/architecture.md](docs/architecture.md).

| Layer | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 3.5, Flyway |
| Web app | React 19, TypeScript, Fluent UI, Vite |
| Data | PostgreSQL 16, with H2 for development and tests |
| Files | S3-compatible storage (SeaweedFS in Compose) or a filesystem/NFS |
| Search | OpenSearch with Apache Tika, with embedded Lucene for development |
| Office | Collabora Online over WOPI, WebDAV for desktop Office |
| Identity | Keycloak |

## Scaling and redundancy

**Can NEXORA run like a SharePoint farm, with several servers for scale and redundancy? Partly: the design allows it,
but it does not work out of the box yet.** No multi-server setup has been built or tested. The shipped Compose stack is
one host with a single point of failure at every layer, and `docker compose up --scale backend=3` is not enough. The
findings below come from reading the code.

### What already helps

- Permanent data is in PostgreSQL and the blob store, not on application servers.
- Permissions and admin switches are read from the database on each use, so a change applies to every server at once.
- Database constraints protect paths, version numbers, edit locks and logins, so two servers colliding produces an
  error, not corrupt data.
- Secrets for WOPI tokens and stored credentials come from settings shared by all servers.
- Each worker has a switch (`NEXORA_MAIL_WORKER`, `NEXORA_SEARCH_WORKER`, `NEXORA_WORKFLOW_WORKER`), so one server can
  do the background work.
- Health probes are available for a load balancer at `/actuator/health`.

### SharePoint farm roles and their NEXORA equivalents

| SharePoint farm | NEXORA | Can it have several servers today? |
| --- | --- | --- |
| Load balancer | nginx or a hardware balancer | Yes, with new configuration. See the gaps below |
| Front-end servers | `backend` | Yes, with sticky sessions |
| Application server, timer service | One `backend` with workers on | **No.** Only one server may run them, and failover is manual |
| Distributed Cache | Nothing. Sessions live in each server's memory | **No.** Losing a server signs its users out |
| SQL Server Always On | PostgreSQL with Patroni | Yes for failover. All queries go to the primary, so a bigger server is the only way to add database capacity |
| Remote blob storage | S3 cluster such as Ceph, or NFS | Yes, through one endpoint address |
| Search servers | OpenSearch and Tika | Yes, through one address. The index needs its replica count raised from the current 0 |
| Office Online Server | Collabora Online | Yes, if each document is routed to one instance |
| AD, ADFS | Keycloak | Yes, it has its own clustering |
| Central Administration | `/admin` | Reachable on every server, but logs and health show one server. A scheduled backup starts once, however many servers share the database |
| "Join farm" wizard | Start another `backend` with the same settings | No wizard, and no list of servers in the farm |

### Target design

```
                              Users, Office, WebDAV clients
                                          │
                           ┌──────────────▼──────────────┐
                           │  Load balancers (2), one IP │
                           │  TLS, sticky routing        │
                           └───┬──────────┬──────────┬───┘
                               │          │          │ route by WOPISrc
          ┌────────────────────┘          │          └─────────────┐
          ▼                               ▼                        ▼
   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        ┌─────────────┐
   │  backend 1  │  │  backend 2  │  │  backend 3  │        │ Collabora   │
   │  web and    │  │  web        │  │  web        │        │ 1..n        │
   │  workers    │  │             │  │             │        └─────────────┘
   └──────┬──────┘  └──────┬──────┘  └──────┬──────┘        ┌─────────────┐
          └────────────────┼────────────────┘               │ Keycloak 2+ │
                           │                                └─────────────┘
       ┌───────────────┬───┴───────────┬───────────────┐
       ▼               ▼               ▼               ▼
┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐
│ PostgreSQL │  │ S3 cluster │  │ OpenSearch │  │ Tika 2+    │
│ primary +  │  │ replicated │  │ 3 nodes    │  │ no state   │
│ standbys   │  │ storage    │  │ replicas   │  │            │
│ (Patroni)  │  │ (or NFS)   │  │            │  │            │
└────────────┘  └────────────┘  └────────────┘  └────────────┘
```

### What this design would give

| If this fails | Effect once the gaps are closed |
| --- | --- |
| A web server | The balancer stops using it. Its users sign in again, and uploads in progress on it must restart |
| The worker server | Email, search updates and rules wait, losing nothing, until an administrator turns workers on elsewhere |
| PostgreSQL primary | Patroni promotes a standby, with a short interruption |
| An object store or OpenSearch node | The cluster continues if its replicas are set up |
| OpenSearch completely | Search falls back to a name match and marks itself degraded. Nothing else stops |
| A Collabora instance | People editing there are interrupted and must reopen the document. Saved versions are safe |

### Gaps to close

**Settings needed for several servers, with no code change:**

1. Replace the nginx routing, which points at a single `backend` name, with sticky sessions by cookie. Route `/dav` by
   client IP, because Basic sign-in clients may not keep cookies. The Compose file also pins host ports for `web` and
   `collabora`, so they cannot be scaled on one host.
2. Use S3 or NFS for files and OpenSearch for search. The default Lucene index belongs to one server.
3. Set the same `NEXORA_WOPI_TOKEN_SECRET` and `NEXORA_ADMIN_SECRET` on every server.
4. Turn off the three workers and `NEXORA_DIRECTORY_SYNC` on all servers but one. **With two workers on, people get
   duplicate email and rules run twice**, because workers do not claim rows.
5. Put `NEXORA_BACKUP_DIR` on a shared volume. Backups are written by whichever server took the request.
6. Start one server first on a new database, so that two servers do not both create the first administrator.
7. Route Collabora by `WOPISrc` hash, so everyone editing a document reaches one instance. Co-authoring depends on it.
   The bundled `collabora/code` image is the unsupported development edition, so check Collabora's limits and licensing
   before a farm goes live.

**Code changes needed:**

| Problem today | Why it matters | Fix |
| --- | --- | --- |
| `JobService.markInterrupted` runs on every start and fails every queued or running job | Restarting one server marks healthy jobs on other servers as failed | Record which server owns a job, and fail only that server's jobs |
| Job cancellation is kept in one server's memory | Cancel does nothing if the request reaches another server | Store the request in the `admin_job` row |
| Sessions are in server memory | A lost server signs people out, and stickiness is mandatory | Store sessions in PostgreSQL or Redis (Spring Session) |
| Only one worker can run | That server is a single point of failure for background work | Claim rows with `FOR UPDATE SKIP LOCKED`, so every server can work and take over automatically |
| A job dies with its server | Long backups and migrations stop and do not resume elsewhere | Add heartbeats and pick up abandoned work. Migration reruns already skip finished items |
| WebDAV ghost files are in memory | Moving to another server during an Office save can break that save | Keep them in shared storage |
| Logs, health and server status show one server | Administrators cannot see the whole farm | Add a server table with heartbeats and a Servers page |
| Blob deletion can race with an identical upload | The new version can point at a deleted file. The window is short, and it can already happen on one server | Delete on a delay, or re-check inside a lock |

**Still to prove:** failover under real load, rolling upgrades (Flyway migrates on start, and old servers have not been
tested against a newer schema) and capacity. Set capacity numbers only after a load test.

## Run it

### With Docker (HTTPS, single sign-on, Office editor)

```sh
cd deploy
cp .env.example .env              # change the passwords
./scripts/make-dev-certs.sh       # or put your own tls.crt / tls.key in deploy/certs
docker compose up -d --build
```

Open https://localhost:8443 (trust `deploy/certs/ca.crt` first) and choose **Sign in with your organization
account**. The bundled demo directory has `jdoe` / `Demo-Pass-1` (an administrator through the AD group
*NEXORA Admins*), `msmith` / `Demo-Pass-2` and `atan` / `Demo-Pass-3`. The local break-glass account is `admin`
with `NEXORA_BOOTSTRAP_ADMIN_PASSWORD`. With `NEXORA_DEMO_DATA=true` you also get the local users
`alice` / `alice-demo` and `bob` / `bob-demo`.

To connect real Active Directory, Kerberos and AD FS, see [docs/identity.md](docs/identity.md).

The stack runs PostgreSQL, SeaweedFS (S3), Keycloak, Collabora Online (port 9980), OpenSearch, Apache Tika, Mailpit
(test mail at http://localhost:8025), the API and the web app.
To co-author, open the same Word/Excel/PowerPoint file as two users (for example alice in one browser and
admin in a private window): both join the same live session and see each other's cursors.

### Without Docker (development)

Needs Java 21+ and Node 22+ with pnpm.

```sh
# API on :8080, embedded H2 database and blobs in backend/data/
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Web app on :5173 (proxies /api to :8080)
cd frontend
pnpm install
pnpm dev
```

The local profile signs in `admin` / `admin` and creates the demo users and the `/sites/intranet` site.
For Office editing in this mode, also run the `collabora` service from `deploy/` (`docker compose up -d collabora`);
it reaches the host backend through `host.docker.internal:8080`.

### Desktop Office and mapped drives

Every library and folder is reachable over WebDAV at `/dav` + its path, for example
`http://localhost:8088/dav/sites/intranet/Shared Documents`. In the web app:

- the file menu shows **Open in Word / Excel / PowerPoint**, which hands the file to the desktop app through
  `ms-word:ofe|u|…` links;
- the library toolbar shows **Map network drive**, with the address and commands for Windows, macOS and Linux.

**HTTPS is required for Windows clients.** Windows and Office send passwords to WebDAV servers only over
HTTPS with a trusted certificate. The Docker stack serves HTTPS; on test machines trust `deploy/certs/ca.crt`.
Directory users sign in to WebDAV with their AD user name and password.

Desktop Office signs in with the Windows credential prompt by default. Microsoft 365 Apps block that prompt
unless the NEXORA host is in Office's Basic-prompt allow list; see
[docs/identity.md](docs/identity.md#desktop-office-sign-in-open-in-word-mapped-drives), which also shows how to
switch to a NEXORA sign-in window (`NEXORA_OFFICE_SIGN_IN=forms`).

### Tests

```sh
cd backend && ./mvnw test        # API integration tests (H2 + temp blob store)
cd frontend && pnpm typecheck
```

The search engines and Tika also have tests against real servers, which the commands above skip:

```sh
NEXORA_TEST_OPENSEARCH_URL=http://localhost:9200 NEXORA_TEST_TIKA_URL=http://localhost:9998   ./mvnw test -Dtest='OpenSearchEngineIT,TikaTextExtractorIT'
```

## Not built yet

**Next in plan order:**

1. **Office polish:** WOPI proof-key validation.
2. **Migration:** validate on a real farm, then add subsites, multi-value lookups and managed metadata columns.
3. **Content types** and a managed-metadata term store.

**Also missing:**

- Multi-server operation, covered in [Scaling and redundancy](#scaling-and-redundancy), and a Kubernetes or Helm install.
- Retention, records management, eDiscovery and legal holds.
- BPMN workflows and custom forms. The rule engine has no branches or long-running processes.
- Calculated columns, and item-level list security.
- A OneDrive-style sync client, and the SharePoint REST compatibility layer.
- Rate limiting, which matters if a public site faces the internet.
- Prometheus metrics: `application.yml` lists the endpoint, but the Micrometer Prometheus library is not installed.

Each guide in [docs/](docs/) ends with the known limits of its area.

## Repository layout

```
backend/    Spring Boot modular monolith (Java 21)
  identity/   users, groups, authentication, bootstrap, anonymous access
  identity/sso/  OIDC login, directory provisioning and sync, Basic/bearer auth for directory users
  content/    content tree, versions, columns, library settings, edit locks
  acl/        permission levels and the single permission-check service
  storage/    BlobStore: filesystem and S3
  audit/      audit events
  lists/      saved views and item history
  sharing/    share links, sharing with people
  alerts/     alert subscriptions and summaries
  mail/       email outbox
  workflow/   approvals, reviews and rules
  search/     search engines (OpenSearch, Lucene), document reading (Tika), indexing queue
  pages/      modern pages, news, web parts, page templates
  portal/     site templates, branding, navigation, hubs, link trimming
  office/     WOPI host for the browser Office editor
  dav/        WebDAV for desktop Office and mapped drives
  admin/      Central Administration: farm status, jobs, backups, logs
  migration/  SharePoint scan, copy, delta sync and redirects
  transfer/   site packages for export, import and restore
  tools/      Keycloak provisioner (one-shot: realm, clients, AD/LDAP, Kerberos, AD FS)
  api/        REST controllers and DTOs (/api/v1)
frontend/   React + TypeScript + Fluent UI web app
deploy/     Docker Compose, certificates script, demo LDAP directory, AD FS script, showcase site generator
docs/       architecture notes and administrator guides
```

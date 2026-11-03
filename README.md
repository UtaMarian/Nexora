# NEXORA

A self-hosted, Linux/container-native document and intranet platform: a replacement for SharePoint Server
for organizations that must stay on-premises. See the
[development plan](SharePoint%20Server%20Replacement%20–%20Development%20Plan.md) for the product strategy.

This repository holds the **MVP core**: sites, document libraries, versioning, check-out/in, metadata
columns, a SharePoint-compatible permission model, a recycle bin, an audit log, a REST API, a web app, and
**browser Office editing with real-time co-authoring** (Collabora Online over WOPI).

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

The stack runs PostgreSQL, SeaweedFS (S3), Collabora Online (port 9980), the API and the web app.
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

### Tests

```sh
cd backend && ./mvnw test        # API integration tests (H2 + temp blob store)
cd frontend && pnpm typecheck
```

## What works

| Area | Status |
| --- | --- |
| Team sites with Owners / Members / Visitors groups and a default Documents library | ✅ |
| Libraries, folders, upload (multi-file, drag and drop), download, rename, move | ✅ |
| Major versions, minor (draft) versions + publish, version history, restore, delete, version limits | ✅ |
| Check-out with a private draft, check-in, discard; "require check-out" libraries | ✅ |
| Custom columns (text, note, number, date, yes/no, choice) with validation | ✅ |
| Permission levels Read / Contribute / Edit / Full Control, inheritance, break/reset, nested groups | ✅ |
| Security-trimmed listings and search; inaccessible items return 404 | ✅ |
| Recycle bin with restore and permanent delete (blob garbage collection) | ✅ |
| Audit log of reads, writes and permission changes; mirrored to the `nexora.audit` logger for SIEM | ✅ |
| SharePoint-style URLs (`/sites/hr/Shared Documents/a.docx`) in the API (`/api/v1/resolve`) and the browser | ✅ |
| Storage on S3-compatible object stores or a filesystem/NFS, content-addressed and de-duplicated | ✅ |
| Browser sessions with CSRF protection; HTTP Basic for scripts | ✅ |
| Browser editing of Word, Excel, PowerPoint and OpenDocument files with real-time co-authoring (WOPI + Collabora) | ✅ |
| Desktop Office (Word, Excel, PowerPoint) open/save via WebDAV with locks; "Open in Word" links | ✅ |
| Map a library as a network drive (Windows, macOS, Linux); Office "safe save" keeps version history | ✅ |
| Site templates: Team, Communication, Project and Intranet portal (libraries, lists, navigation, theme, home page) | ✅ |
| Change the look: 8 themes, header layout/background, logo, top navigation, home page (welcome, quick links, news) | ✅ |
| Lists with templates (Tasks, Issues, Contacts, Announcements, Links, Events, Custom), sortable/filterable views, item forms; Person and Hyperlink columns | ✅ |
| HTTPS everywhere (nginx TLS for app, API, WebDAV, Keycloak and Collabora) | ✅ |
| Single sign-on via Keycloak: OIDC + PKCE, AD/LDAP users and nested groups, Kerberos, AD FS (SAML) | ✅ |
| Directory sync (AD groups usable in permissions before first sign-in), admin group mapping, Basic and bearer auth for directory users | ✅ |
| OpenAPI spec at `/v3/api-docs`, Swagger UI at `/swagger-ui.html` (API port) | ✅ |

## Not built yet (next slices, in plan order)

1. **Search**: OpenSearch + Apache Tika + OCR, full-text and security-trimmed (today: name/title match only).
3. **Office polish**: WOPI proof-key validation, "New document" from templates.
4. **Migration tooling**: assess, map, copy with versions and permissions, delta sync, URL redirects.
5. Content types and a managed-metadata term store, saved list views, modern pages with web parts, workflows (v1).

## Layout

```
backend/    Spring Boot modular monolith (Java 21)
  identity/   users, groups, authentication, bootstrap
  content/    content tree, versions, columns, library settings
  acl/        permission levels and the single permission-check service
  storage/    BlobStore: filesystem and S3
  audit/      audit events
  portal/     site templates, branding (theme, header, logo, navigation, home page)
  office/     WOPI host for the browser Office editor
  dav/        WebDAV for desktop Office and mapped drives
  identity/sso/  OIDC login, directory provisioning and sync, Basic/bearer auth for directory users
  tools/      Keycloak provisioner (one-shot: realm, clients, AD/LDAP, Kerberos, AD FS)
  api/        REST controllers and DTOs (/api/v1)
frontend/   React + TypeScript + Fluent UI web app
deploy/     Docker Compose, certificates script, demo LDAP directory, AD FS registration script
docs/       architecture notes
```

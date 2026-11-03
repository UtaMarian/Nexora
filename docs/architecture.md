# Architecture notes

These notes record the design decisions behind the MVP core and how they follow the development plan.

## Shape

A modular monolith (one deployable) on PostgreSQL plus S3-compatible storage. Modules are Java packages
with clear seams (`identity`, `content`, `acl`, `storage`, `audit`, `api`), so a module can become its own
service later if scale demands it. The web app uses only the public REST API.

## Content tree

Every site, library, folder and file is a row in `node`:

- a stable UUID `id`, which never changes;
- a SharePoint-style server-relative `path` such as `/sites/hr/Shared Documents/Policies/Leave.docx`, so
  migrated links resolve (`GET /api/v1/resolve?path=…`).

Names are matched case-insensitively and follow SharePoint's character rules. A rename or move rewrites the
paths of all descendants in one `UPDATE`. A delete is a soft delete of the whole subtree, tagged with
`deleted_root_id`, which is what the recycle bin shows and what restore undoes.

## Versions and blobs

- Blobs are **content-addressed** (`sha256/ab/cd/<sha>`). Identical uploads are stored once, and versions
  (including a "publish", which reuses the draft's bytes) share blobs.
- Blob deletion runs **after commit**, and only when no version and no working copy still references the
  key. A rolled-back transaction can therefore never leave a row pointing at a missing blob.
- Version numbering follows SharePoint: major only (1.0, 2.0) or major/minor (0.1, 0.2, publish → 1.0, then 1.1).
- A **check-out** keeps a private working copy on the node (`working_*` columns). The person who checked it
  out downloads the draft; everyone else still gets the current version. Check-in turns the draft into a
  version, and discard drops it.

## Permissions

`PermissionService` is the only place where access is decided; API, search and the future WebDAV/WOPI
endpoints all call it.

- Levels: Read ⊂ Contribute ⊂ Edit ⊂ Full Control (SharePoint defaults). The effective level is the
  highest level granted to the user or to any group they belong to, transitively.
- A node either inherits from its parent or has unique permissions (`inherits_permissions = false`, with
  rows in `acl_entry`). Sites are always permission roots. Breaking inheritance copies the parent's
  entries, as SharePoint does.
- No VIEW right means **404**, so the API does not reveal that an item exists. VIEW without the needed
  right means **403**. Rights are checked before name conflicts for the same reason.
- Listings resolve each distinct ACL source once (`PermissionService.levels`).

## Authentication

- Browser: `POST /api/v1/auth/login` creates a server session. Writes require the `X-XSRF-TOKEN` header
  matching the `XSRF-TOKEN` cookie.
- Scripts: HTTP Basic on each request. Requests with an `Authorization` header carry no ambient
  credentials, so CSRF does not apply to them.
- Planned: Keycloak (OIDC/SAML/Kerberos) replaces local passwords. `principal.external_id` holds the
  AD SID / subject for migration mapping.

## Office editing and co-authoring (WOPI)

NEXORA is a WOPI *host*; Collabora Online is the WOPI *client* (the editor).

```
browser ──POST access_token──▶ Collabora (iframe, :9980) ──WOPI──▶ backend /wopi/files/{id}
   ▲                                   │                                  │
   └──────── /api/v1/nodes/{id}/office-session ◀──────────────────────────┘
```

1. The web app asks `POST /api/v1/nodes/{id}/office-session`. NEXORA reads Collabora's discovery XML for
   the editor URL, and signs an access token (HMAC, user + file + expiry + browser origin).
2. The web app posts the token into an iframe at the editor URL, with `WOPISrc` pointing at
   `/wopi/files/{id}`.
3. Collabora calls CheckFileInfo, GetFile and PutFile server-to-server. **Every call re-checks
   permissions**, so revoking access stops an open editor at its next save.
4. Everyone who opens the same file gets the same `WOPISrc`, so Collabora puts them in one live
   session. That is the co-authoring.

Rules:

- **Saves are versions.** PutFile goes through `DocumentService.saveContent`, the same path as an upload,
  so version numbering, check-out drafts, version limits and the audit log all apply.
- **Check-out wins.** If someone else has the file checked out, or the library requires check-out and the
  user has not checked it out, the editor opens read-only.
- **Conflicts.** If the file changed outside the session (for example an upload), PutFile answers `409`
  with `COOLStatusCode 1010` and Collabora asks the user what to do.
- **Not exposed.** `/wopi/` is reachable only on the internal network (the nginx web container returns
  404). The token secret must be identical on all backend nodes and stable across restarts.
- WOPI locks are not used (`SupportsLocks=false`), because Collabora coordinates its own sessions. Locks
  become necessary for desktop Office over WebDAV.

## Databases

Flyway migrations live per vendor (`db/migration/postgresql`, `db/migration/h2`). PostgreSQL uses
`JSONB` and a partial unique index on live paths. H2 (local dev and tests) enforces path uniqueness
in the service layer only.

## Known gaps

- The Compose stack uses SeaweedFS for S3, because MinIO stopped publishing container images. Any
  S3-compatible store works. The PostgreSQL + S3 path has been smoke-tested, but the automated tests
  run on H2 and the filesystem store.
- WOPI proof keys (`X-WOPI-Proof`) are not validated yet; the `/wopi` endpoints rely on network isolation
  plus signed tokens.
- Search matches names and titles only, until the OpenSearch module lands.
- Draft visibility for minor versions (SharePoint's "who can see drafts") is not implemented: readers see
  the latest version, published or not.
- New files in "require check-out" libraries are not left checked out to the uploader.

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

## Databases

Flyway migrations live per vendor (`db/migration/postgresql`, `db/migration/h2`). PostgreSQL uses
`JSONB` and a partial unique index on live paths. H2 (local dev and tests) enforces path uniqueness
in the service layer only.

## Known gaps

- The PostgreSQL + MinIO path is configured but was not exercised in this first slice (tests run on H2 and
  the filesystem store). Run `docker compose up` once and smoke-test before relying on it.
- Search matches names and titles only, until the OpenSearch module lands.
- Draft visibility for minor versions (SharePoint's "who can see drafts") is not implemented: readers see
  the latest version, published or not.
- New files in "require check-out" libraries are not left checked out to the uploader.

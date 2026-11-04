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
- Single sign-on: OIDC against Keycloak, which federates AD/LDAP and Kerberos and brokers AD FS. Directory
  principals carry `external_id` (`kc:<id>`, `kc-group:<path>`, `idp-group:<name>`), and their group memberships
  are owned by the directory. See [identity.md](identity.md).

## Portals, branding and lists

- **Site templates** (`portal/SiteTemplate`): Team, Communication, Project and Intranet portal. Creating a
  site from a template provisions libraries and lists, then sets the navigation, theme, header and home page,
  all in one transaction.
- **Design** (`portal/SiteDesign`) lives in the site node's `settings.design`: theme preset, header (layout,
  background, title, logo), top navigation, and home page (welcome, quick links, news source, sections).
  Every link is checked server-side (`SafeUrls`): only `https://`, `http://`, `mailto:` or `/sites/...`, so
  stored navigation can never carry `javascript:` links. The web app derives a full Fluent UI brand ramp from
  the preset and applies it to everything inside the site.
- **Logos** live in `site_asset`, separate from the content-addressed document blobs, so blob garbage
  collection can never remove them. The image type is detected from the file's first bytes (PNG, JPEG, GIF,
  WebP only; no SVG) and served with `nosniff` and a sandbox CSP.
- **Lists** are `LIST` nodes, and their items are `LIST_ITEM` nodes named by a per-list numeric ID
  (`/sites/x/Tasks/3`). They reuse the column engine, permissions, inheritance, recycle bin and audit log of
  libraries. List templates provision SharePoint's standard columns. New column types: `URL` (safe links
  only) and `PERSON` (stored as an `{id, login, displayName}` snapshot). Lists are not exposed over WebDAV.

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

## WebDAV (desktop Office, mapped drives)

A servlet at `/dav/*` serves RFC 4918 class 1+2 (PROPFIND, PROPPATCH, GET, PUT, DELETE, MKCOL, COPY, MOVE,
LOCK, UNLOCK) over the same namespace as SharePoint: `/dav/sites/<site>/<library>/<folders>/<file>`. It calls
the same services as the REST API, so permissions (including 404s for hidden items), versioning, check-out
and auditing apply unchanged. Its security chain is stateless HTTP Basic with a `WWW-Authenticate` challenge,
and OPTIONS is anonymous so clients can discover DAV support.

- **Edit locks.** LOCK creates a row in `edit_lock` (exclusive, expiring, at most 24 h). The lock is
  enforced on every write path, not only WebDAV: uploads, rename/move/delete and the browser editor. While
  Word has a file open, others get it read-only, and the web app shows who has it open. Locks are distinct
  from check-out: they expire on their own and don't hide changes.
- **Empty placeholders.** Explorer creates a file empty and then uploads it. A single empty version made by
  the same user in the last 5 minutes is replaced instead of kept, so history doesn't start with an empty 1.0.
- **Ghost files.** Temp and lock files that desktop apps create (`~$*`, `~WR*.tmp`, `*.tmp`, `.~lock.*`,
  `desktop.ini`, `Thumbs.db`, `._*`) live in a per-user in-memory store for 2 hours. They never become
  documents.
- **Office safe save.** On a mapped drive, Word saves by writing `~WRL1.tmp`, renaming `Doc.docx` to
  `~WRD2.tmp`, renaming `~WRL1.tmp` to `Doc.docx`, and deleting `~WRD2.tmp`. The first rename only records an
  *alias* (the real document stays put and is hidden from that user's listings). The second rename becomes a
  new version of the same document, so history, permissions and links survive.

## Search

```
any change ─ same transaction ─▶ search_queue ──▶ index lane ──▶ OpenSearch / Lucene
                                                     │  ▲
                                                 needs text
                                                     ▼  │
                                                 text lane ──▶ Tika ──▶ search_text
```

- **Change capture.** `AuditService` already sees every write in the writer's transaction, including bulk subtree
  updates that never touch a JPA entity, so it feeds `SearchQueue`. A rollback leaves no work, and a crash loses none.
  Writes without an audit event (title edits, page publishing, each purged node) enqueue directly.
- **Rows mean "make the index match the database now".** They carry no data, so repeats are harmless and order does
  not matter. The worker coalesces them and retries by leaving them in place.
- **Two lanes.** Reading can take minutes, so the index lane indexes a document at once without its text, and the text
  lane queues it again when the text is ready.
- **Text is stored by blob** in `search_text`. Rebuilding the index never repeats OCR, and identical files are read once.
  The blob collector deletes the text with the blob, so a purged document leaves nothing behind.
- **Tika runs in its own container** with a memory limit, because it parses untrusted files.
- **Drafts stay out.** The index sees the current version and the published page only, never a checked-out working copy
  or a page draft.

Security is enforced twice:

1. Each document stores `aclSource`, the node whose ACL governs it. At query time the engine filters by the ACL sources
   the caller is on, which keeps counts and facets honest. A grant or revoke therefore applies to the next query with
   no reindexing. Only moves and inheritance changes reindex, because they change `aclSource`.
2. Every hit is then checked against the database through `PermissionService`, so an index that is behind cannot show
   anything. Result details also come from the database; the index decides what matches, not what is displayed.

Snippets mark matches with private-use characters, and the API returns them as segments, so no HTML ever reaches the
page. If the engine is down, `SearchService` falls back to the old name match and sets `degraded`.

## Lists, sharing and alerts

See [lists-sharing-alerts.md](lists-sharing-alerts.md) for what these do.

- **Attachments are `FILE` nodes under a `LIST_ITEM`.** They inherit versions, recycle bin, permissions, search and
  export without any new storage.
- **Lookup values store `{lookupId, title}`.** `lookupId` is the item's number in its list, not a node id, because
  numbers survive SharePoint migration and site export while node ids do not. The title is a snapshot for display, kept
  current when the target item is renamed.
- **Views live in the list's `settings`**, so they travel with exports. The web app applies them to loaded items.
- **Sharing with people is an ordinary ACL entry**, so search, WebDAV and everything else already respect it.
  `share_grant` only remembers who shared what, for *Shared with me*.
- **Email and alerts follow the same outbox idea as search.** `AuditService` hands changes to `AlertService` in the
  writer's transaction, and workers do the slow part. Access is checked per recipient at send time.
- **Link access never reuses the signed-in path.** `LinkAccessService` serves only the current version, and inside a
  folder it compares ACL sources: a node with its own permissions has a different source from the shared root, so it is
  unreachable through the link.

## Workflows

See [workflows.md](workflows.md) for what these do. The development plan names a BPMN engine; this first version is a
small rule engine in the monolith, which covers approvals and simple automation with no extra service.

- **Events follow the search and alert pattern.** `AuditService` hands changes to `WorkflowEvents` in the writer's
  transaction, and `WorkflowEngine` handles them in the background.
- **The Workflow account breaks loops.** Events from it are ignored, so no combination of rules can feed itself.
- **Changes to an approval take a row lock** (`ApprovalRepository.lockedById`), as the first load in the transaction.
  Simultaneous answers run one at a time, so the last one always sees the others and finishes the approval.
- **A `STALE` event guards content.** It is written with the change that makes an approval out of date, so `decide`
  refuses at once, before the worker has cancelled the request.
- **Definitions name things.** Rules refer to logins, column names, folder paths and list names, not ids, so they survive
  export. Steps keep names as well as ids, so the record outlives an account.

## Databases

Flyway migrations live per vendor (`db/migration/postgresql`, `db/migration/h2`). PostgreSQL uses
`JSONB` and a partial unique index on live paths. H2 (local dev and tests) enforces path uniqueness
in the service layer only.

## Known gaps

- The Compose stack uses SeaweedFS for S3, because MinIO stopped publishing container images. Any
  S3-compatible store works. The PostgreSQL + S3 path has been smoke-tested, but the automated tests
  run on H2 and the filesystem store.
- Kerberos is configured at Keycloak (browser sign-on). WebDAV clients use Basic with the AD password, not
  SPNEGO, and AD FS users without an AD/LDAP connection have no password for WebDAV (bearer tokens only).
- Ghost files and aliases are held in memory per backend node; with several nodes, WebDAV needs sticky sessions.
- Lists live at `/sites/<site>/<List>`, not SharePoint's `/sites/<site>/Lists/<List>`; the migration
  redirect service maps old list URLs.
- Item-level
  security ("users see only their own items") is not implemented.
- WOPI proof keys (`X-WOPI-Proof`) are not validated yet; the `/wopi` endpoints rely on network isolation
  plus signed tokens.
- Draft visibility for minor versions (SharePoint's "who can see drafts") is not implemented: readers see
  the latest version, published or not.
- New files in "require check-out" libraries are not left checked out to the uploader.

## Modern pages

Pages and news posts live in `site_page` (current published canvas plus a draft) and `site_page_version`
(one snapshot per publish), apart from the node tree, at `/sites/<site>/SitePages/<name>.aspx`. The name
`SitePages` is reserved inside sites. A canvas is a list of sections (layout `one`, `two`, `three`,
`oneThirdLeft`, `oneThirdRight`; background `none`, `soft`, `strong`), each with columns of web parts:

| Web part | Settings | Renders |
| --- | --- | --- |
| `text` | `html` | rich text, sanitised on the server (jsoup allow-list: headings, emphasis, lists, quotes, links) |
| `image` | `fileId`, `alt`, `caption`, `size` | an image from the site's *Site Assets* library |
| `documents` | `libraryId`, `title`, `limit` | latest files of a library |
| `list` | `listId`, `title`, `limit` | latest items of a list |
| `news` | `title`, `limit`, `layout` | the site's latest published news posts |

Web parts store ids, never copies, so readers only see what their permissions allow; referenced content must
belong to the same site. Rights come from the site: View reads published pages, Contribute creates, edits and
publishes, deleting needs Delete Items and choosing the home page needs Manage Web. Draft saves carry a
revision number, so two editors cannot overwrite each other silently (409 `page_changed`).

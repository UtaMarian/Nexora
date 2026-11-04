# Farm administration

Farm administrators open **Central Administration** from the top bar (`/admin`). Everything there uses
`/api/v1/admin/**`, which requires the administrator role.

| Page | What it does |
| --- | --- |
| Overview | Counts, storage, health of database, storage, Collabora, directory sync and backup folder, server status |
| Sites | Every site with owners, size and last change; create (template and owner), back up, delete |
| Backup and restore | Farm backup, site restore, package download and import |
| SharePoint migration | Connect farms, scan, copy, delta sync, test old links |
| Search | Health, work waiting, unreadable documents, rebuild the index. See [search.md](search.md) |
| Sharing and email | Active links, the switch for links without sign-in, email status and test message. See [lists-sharing-alerts.md](lists-sharing-alerts.md) |
| Jobs | Status, log and cancel for background work. Two jobs run at a time |
| Server logs | The latest 5,000 log lines, filtered by level and text. Use container logs for older lines |
| Audit log | Everything recorded across the farm |
| Users | Enable or disable accounts, grant the administrator role |
| Directory sync | AD/LDAP sync status and manual sync |
| Settings | The active configuration, read-only. Secrets are never shown |

## Backups

Backups are written to `NEXORA_BACKUP_DIR` (Compose volume `backup-data`, mounted at `/var/lib/nexora/backups`).
A **site package** is a ZIP with a `manifest.json` and one `blobs/<sha256>` entry per distinct file version. It
holds folders, files with all versions, lists, columns, metadata, permissions, site groups, pages, design and
logo. A **farm backup** is one package per site plus `directory.json` with users, groups and memberships.

- Not included: recycle bins, the audit log, job history, migration tasks, check-out state.
- Restores always create a new site and never overwrite one. A site at the same URL is skipped. To return a site
  to its own URL, purge it from the recycle bin first, or restore it under another URL.
- Restoring the directory only adds what is missing. Existing accounts are not changed.
- People are matched by login, then e-mail. Content by unknown people is attributed to the administrator who
  restores it, and their permissions are skipped; the job log lists them.
- File content is verified against its checksum, and a damaged package stops the restore with nothing created.
- `directory.json` contains password hashes of local accounts. Treat the backup folder like the database, and
  copy it off the server: a volume on the same host does not protect against losing that host.
- Backups are started by hand. There is no scheduler yet. For point-in-time recovery of a large farm, also use
  `pg_dump` or snapshots of the database and object store.

## SharePoint migration

NEXORA reads the farm through its REST API (`/_api`) from the backend, so nothing is installed on the farm. It
expects SharePoint 2013 SP1, 2016, 2019 or Subscription Edition, and an account with Site Collection
Administrator or Full Read on the sites. Stored credentials are encrypted with `NEXORA_ADMIN_SECRET`; if that
is empty, the WOPI token secret is used. Keep the value stable, or credentials must be entered again.

Typical flow:

1. **Add farm**, then **Test**. **Find sites** lists site collections through SharePoint search.
2. **New task**: one SharePoint site to one NEXORA site. Add user mappings if account names differ.
3. **Full scan**: lists libraries, lists, sizes, unsupported items and file issues. Nothing is written.
4. **Start copy**. A failed item is logged and the run continues. Run it again to retry; finished items are skipped.
5. **Sync changes** as often as needed. It copies only what changed since the last run: new versions, new files,
   renames and moves, metadata and list item changes. With the task option on, items deleted in SharePoint move
   to the NEXORA recycle bin.
6. Make SharePoint read-only, run a last sync, then point the old host name at NEXORA.

### What is copied

| Source | Result |
| --- | --- |
| Document and picture libraries, folders | Libraries and folders, same names |
| Files | Every version, keeping labels, authors, dates and comments |
| Custom lists, Tasks, Issues, Contacts, Announcements, Links, Events | Lists; items keep their SharePoint ID |
| Text, Note, Number, Currency, DateTime, Boolean, Choice, URL, Person columns | Columns and values |
| Site, list, folder, file and item permissions | Permissions. Full Control, Design/Edit, Contribute, Read map to the NEXORA levels |
| Owners, Members and Visitors groups | The site's three groups, with members |
| Other SharePoint groups | New site groups, with members |
| AD users and groups | Matched to accounts by user name or e-mail, and by group name |

### What is not copied

- Subsites: create one task for each.
- Wiki and modern pages, web parts, workflows, InfoPath forms, custom solutions.
- List item version history.
- Multi-value lookup and managed metadata columns. Rich text columns keep their text only. Multi-choice values become one
  combined value.
- Folder structure inside lists: items are copied flat.
- On sync: people removed from a group, and items whose unique permissions were reset to inherited in
  SharePoint. Both stay as they were in NEXORA, which is the stricter state.

### Permissions during a copy

Content with its own permissions in SharePoint is created locked, so only administrators can open it until its
real permissions are applied. If reading a list's restrictions fails, that list is not copied at all. If applying
permissions fails, or none of the people named exist in NEXORA, the content stays locked and the job log says so.
Turning off **Copy permissions** removes this protection: everything then follows the new site's permissions.

Run a directory sync before migrating, so AD users and groups exist in NEXORA.

### Old links

The source-to-target map stays with the task. **Deleting a task stops redirects for that site.** Once the old
host name points at NEXORA, these work: file and folder URLs, `Forms/AllItems.aspx` and other views,
`DispForm.aspx?ID=3` and `EditForm.aspx`, `Doc.aspx?sourcedoc={guid}` and `WopiFrame.aspx`, and `/:w:/r/...`
sharing links. Tokenised sharing links (`/:w:/g/...`) cannot be resolved. A reader who may not open the target
lands on the closest parent they can see. Point a DNS name or proxy at NEXORA and add its certificate; if the
target site name differs from the old one, redirects follow the map.

### Testing status

The migration is tested against a simulated farm built from Microsoft's REST documentation, using Basic
authentication. **It has not been run against a real SharePoint farm, and NTLM sign-in is untested.** Try it on a
small site first and compare the result before planning a production run.

# New documents, lists, sharing and alerts

## New documents

**New** in a library or folder creates a blank Word, Excel or PowerPoint file and opens it in the browser editor. Files
in the site's **Templates** library appear in the same menu, and choosing one makes a copy. The site needs a library
named exactly `Templates`; the Intranet portal template creates it.

## Lists

**Saved views** appear as tabs above a list. A view chooses its columns, order and filter, and can show as a table, a
month calendar or a gallery. A calendar needs a date column, and can use an end date so an event spans several days. A
gallery uses the first image attached to each item as its cover. People with Edit can create views, and everyone can
use them. One view can open by default.

**Lookup columns** pick an item from another list in the same site. Renaming that item updates everywhere it is used.

**Attachments** are ordinary files under the item, at `/sites/<site>/<list>/<id>/<file>`. They are searched, keep
versions and go to the recycle bin with the item.

**Version history** keeps every save of an item. Restoring an old version adds a new one, so nothing is lost.

SharePoint migration brings over lookup columns and attachments. Site backup and restore keeps views, lookups,
attachments and item history.

## Sharing

Anyone who can edit an item can share it, from **Share** in its menu.

**With people.** They get Can view or Can edit (Contribute), never more, and the item appears under *Shared with me*.
This is a normal permission: the first share gives the item its own permissions, starting from a copy of what it had.
Sharing never lowers access someone already has.

**With a link.** Links are read-only, work for files and folders, and always expire.

| Choice | Options |
| --- | --- |
| Who can open it | People in the organization (must sign in), or anyone with the link |
| Expires | 1 to 365 days (`NEXORA_SHARE_MAX_LINK_DAYS`) |
| Password | Optional. After 8 wrong tries the link locks for 15 minutes |

What protects a link:

- The address holds a 256-bit random token, and the server keeps only a hash, so the database cannot give a link away.
  The address is shown once.
- Visitors only ever get the published version, never a checked-out draft.
- Inside a shared folder, anything with its own permissions stays hidden and cannot be opened.
- A link stops working when it is stopped, expires, or its item is deleted, and every failure looks the same from outside.
- Every download is in the audit log.

**Central Administration → Sharing and email** lists every active link and can stop any of them. Its switch turns off
links that work without signing in, and that also stops existing ones at once. They are allowed by default.

## Alerts

**Alert me** on a library, list, folder, file or item sends an email when someone else changes it or anything in it:
right away, or as a daily or weekly summary. *My alerts* in the account menu lists them.

Access is checked when the email is prepared, so someone who lost access stops hearing about the content. Immediate
alerts wait about a minute, so the autosaves of one editing session become a single email.

## Email

Email goes through an outbox in the database, so a mail outage loses nothing. Failed messages are retried with growing
delays, up to 8 times over about 4 hours. Those that still fail stay in the outbox and count as failed in health.

The Docker stack includes Mailpit, which catches all mail at http://localhost:8025 and delivers nothing. For real email,
set these in `.env`:

| Variable | Meaning |
| --- | --- |
| `NEXORA_MAIL_ENABLED` | `true` to send email |
| `NEXORA_SMTP_HOST`, `NEXORA_SMTP_PORT` | Your mail server |
| `NEXORA_SMTP_USER`, `NEXORA_SMTP_PASSWORD` | Optional sign-in |
| `NEXORA_SMTP_STARTTLS` | `true` to encrypt the connection |
| `NEXORA_MAIL_FROM` | The sender |
| `NEXORA_PUBLIC_HOST` | The host name used for links in email |

Central Administration can send a test message.

## Known limits

- Views are shared by everyone in a list. There are no personal views, and libraries have none yet.
- A lookup holds one value. Multi-value lookups from SharePoint are skipped, with a warning in the migration log.
- If an item used by a lookup is deleted, its old title stays on the rows that used it.
- SharePoint's history of list items is not migrated. Migrated items start their history at their current state.
- Item history has no limit and is not trimmed.
- Share links are read-only. Folders are downloaded one file at a time.
- Sharing a group sends no email to its members; the group's own address is used if it has one.
- Summaries go out a day or a week after the last one, not at a chosen time.
- Email is in English only and has no one-click unsubscribe. People stop alerts under *My alerts*.
- Real delivery has been tested with Mailpit only, not with a production mail server.

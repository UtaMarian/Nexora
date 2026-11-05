# Intranet navigation

Subsites, hub sites, mega menus, four more web parts and page templates. Together they turn separate sites into one
intranet.

## Subsites

A subsite is a full site inside another site, at an address like `/sites/hr/payroll`. It has its own libraries, lists,
pages, navigation and design. Owners add one with **New > Subsite**, and a site can go four levels deep.

When you create a subsite you choose who can get in:

| Choice | Meaning |
| --- | --- |
| Its own groups | It gets its own Owners, Members and Visitors groups, and the parent's people do not get in automatically |
| The same people as the parent | It has no groups of its own, and changes to the parent's permissions apply to it |

A subsite goes with its parent. Deleting the parent sends both to the recycle bin, and restoring brings both back.
Deleting a parent for good also removes the subsite's groups and files. The parent's owners can delete any subsite they
can open, and can restore any deleted subsite from the recycle bin. A subsite's own owners cannot delete it.

Subsites appear on the home page of anyone who can open them, even without access to the parent, and in **Sites** in
Central Administration. On a mapped drive they appear inside their parent folder.

Subsites cannot be moved. Search groups a subsite's results under its top-level site.

## Hub sites

A hub links separate sites. Every site in a hub shows the hub's navigation bar and theme, and the hub can show the news
of all of them.

1. A farm administrator turns the hub on in **Central Administration > Sites**.
2. The hub's owners set its name and navigation in **Change the look > Hub**.
3. Owners of other sites join it in **Change the look > Hub**.

A site's own theme comes back if it leaves the hub. A hub cannot join another hub. Subsites cannot be hubs, and they
follow the hub of their parent.

Joining a hub shares navigation, a theme and news. It does not change permissions. A News web part set to **All sites
in the hub** shows only what the person reading it can open. Someone who cannot open the hub site does not see its name
or navigation, and their menus leave out what they cannot open. See [anonymous-access.md](anonymous-access.md#links-people-cannot-open).

## Mega menus

In **Change the look > Navigation**, or in a hub's navigation, the table button turns a link into a menu. A menu has up
to 6 columns, each with a heading and up to 12 links. Its top link does not need an address.

The menu works with the keyboard. Enter opens it and moves to the first link, Tab moves through the links, and Escape
closes it and returns to the menu's button. On a phone the columns stack.

Each person sees only the links they can open, and a menu with nothing left disappears.

## New web parts

| Web part | Shows | Notes |
| --- | --- | --- |
| Quick links | Tiles or a list of links, with optional descriptions | Up to 24. Links must be site addresses, https, or mailto |
| Events | Upcoming items from a list in the same site | Uses a date column, and takes one from the list if you do not choose. Shows dates, not times. Recurring events are not supported |
| People | Names and roles | Up to 24. Shows initials, not photos. The name is saved with the page, so a later change of name does not appear until the page is edited |
| Embed | A video, map or form from an allowed site | See below |

Web parts that have not been set up ask to be filled in while the page is being edited. Readers do not see them.

### Embedded content

Nobody can paste HTML. An embed is an address, checked against a list of allowed sites in **Central Administration >
Settings**. The defaults are `www.youtube-nocookie.com`, `player.vimeo.com` and `www.google.com`.

- The host name must match exactly, so allowing `forms.example.com` does not allow `sub.forms.example.com`.
- Only https is accepted, and addresses with a user name in them are refused.
- YouTube and Vimeo watch links are changed to their player addresses. YouTube uses the privacy-enhanced player.
- The content runs in a sandboxed frame. It can run its own scripts but cannot navigate the intranet page or read it.
- If an administrator removes a site from the list, existing embeds stop showing and readers see a short message.

Allowing a site means trusting what that site shows. Readers' browsers contact it directly, so its owner can see their
IP addresses, and embeds need access to the internet from the reader's device.

## Page templates

**New page** and **New news post** offer a gallery: Blank, Article, Department home, Team home, Landing page, and
anything saved in that site. **Save as template** on a page keeps a copy of its layout. A page made from a template is
independent, so later changes to the template do not reach it.

Saved templates are not pages. They do not appear in the site's page list and cannot be published. Anyone who can edit
pages in the site can use one. To change a template, save a new one and delete the old one from the gallery.

## Backup and restore

Backing up a site includes its subsites at every level, with their groups, pages, templates and permissions. Restoring
under a new name moves everything to the new address, except that:

- A site restored under a new name is not a hub and does not join one, because it is a copy.
- Links typed into navigation, Quick links and text still point to the old address.

## Not supported

- Migration from SharePoint does not copy subsites. Create one migration task for each. See
  [administration.md](administration.md).
- Audience targeting, which would show links to chosen groups regardless of access.
- Moving a subsite or a site into a different parent.
- More than one level of hub.

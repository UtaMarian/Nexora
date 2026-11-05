# Anonymous access

People can read chosen sites, libraries and lists without signing in, as with SharePoint's anonymous access. It is off
until an administrator allows it, and then nothing is open until an owner opens it.

## Turning it on

1. A farm administrator turns on **Central Administration > Sharing and email > Anonymous access**.
2. A site owner opens **Site permissions** and turns on **Anyone can read this without signing in**.

The choice applies to what you open it on:

| Opened on | Visitors can read |
| --- | --- |
| A site | Everything in the site, except items and subsites with their own permissions |
| A library or list | Only that library or list. Use **Manage access** on it. The rest of the site stays private, and the library stops inheriting permissions |

A subsite with its own groups makes its own choice. A subsite that shares its parent's people follows the parent. To
close something that follows another item, change it on the item the dialog names.

Turning the farm switch off closes everything at once, for visitors and for signed-in people relying on it. Owners'
choices are kept and come back when it is turned on again.

## What visitors can do

Visitors can only read. They can open the home page of public sites, published pages and news, libraries, lists, and
the current version of files, and they can search public content.

They cannot change anything, see drafts, version history, permissions, groups or the recycle bin, or use the Office
editors, alerts, sharing, approvals or mapped drives. Anonymous access can never be raised above Read, and the account
cannot join a group. These limits are in the server, so they hold whatever a site's permissions say.

Signed-in people can read whatever is open too, so public sites appear on everyone's home page and in everyone's
search.

## What to check before opening a site

- **Everyone on your network can read it.** If NEXORA is reachable from the internet, so can everyone there.
- **Names are shown.** Visitors see the display names of the people who created and changed items, and any names in
  person columns. Sign-in names are removed. Contact lists show what is in them, including email addresses.
- **Stopping inheritance copies access.** If an item in an open site stops inheriting, it keeps anonymous access until
  you remove **Anonymous visitors** from it.
- **Nothing limits how much visitors read.** There is no rate limit, so put a public site behind your own reverse proxy
  limits if it faces the internet.

## Backup and restore

Anonymous access is not stored in backups, so a restored site is always private, even when it replaces the original.
Owners open it again.

## People without access

Someone signed in who opens a site they cannot see gets **This site wasn't shared with you**. The answer depends only
on the site in the address, so it does not reveal whether a file or page in that site exists. Addresses in a site they
can open, and sites that do not exist, still show **Not found**.

This tells signed-in people that a site exists at an address they typed. A visitor who is not signed in is asked to sign
in for anything they cannot see, whether it exists or not, and then returns to the same address.

Someone with no sites at all sees a message on the home page. There is no **Request access** button yet.

## Links people cannot open

Each person's menus show only what they can open. This covers site navigation, hub navigation, mega menus, home page
quick links and Quick links web parts. Outside addresses always stay. A menu or column with nothing left disappears.

Someone who cannot open a hub site does not see its name or navigation, in a site that joined it or in a public site.
Their site still shows the hub's theme. A subsite only names its parent to people who can open the parent.

Owners see every link while editing navigation, and people who can edit a page see every link on it. Otherwise saving
would delete the links they cannot open. Links written into text are not checked.

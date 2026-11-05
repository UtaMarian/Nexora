# Workflows

Approvals and reviews ask people to look at a file or list item. Rules do things automatically in a library or list.

## Approvals

Choose **Request approval** on a file or list item, pick the approvers and how it works:

| Option | Meaning |
| --- | --- |
| Everyone must approve | One rejection ends it |
| The first answer decides | The rest are not needed |
| One after another | Each person is asked when the one before approves |

Approvers can be people or groups. For a group, any one member answers. Approvers get an email and see the request
under **Approvals** in the top bar, with a count of what is waiting. They can approve, reject with a reason, or pass it
to someone else. An optional due date sends a daily reminder once it has passed.

The rules that keep an approval honest:

- **It covers what the approvers were shown.** If the file or item changes, the request is cancelled and the person who
  asked is told. Changing only a file's columns does not cancel it, because the document is the same.
- **Nobody approves their own request**, even through a group, and one person counts once.
- **Approvers must be able to open the item.** The request is refused if they cannot, and someone who later loses access
  can no longer answer.
- **One request at a time** per item.

When a document is approved in a library that uses minor versions, the draft is published as the next major version. If
it is checked out or open, it stays a draft and the outcome says so.

## Reviews

**Request a review** collects feedback instead of a decision. Everyone answers *Looks good* or *Changes needed*, the
second with a comment. It finishes as *Reviewed* or *Changes requested*, and all comments go to the person who asked.

## Rules

**Automate** in a library or list needs the Edit level. A rule reads as a sentence: when something happens, only if
these conditions hold, then do these things.

| When | |
| --- | --- |
| Something is added or changed | Files in a library, items in a list. Attachments and folders do not count |
| An approval is approved or rejected | A finished review counts too |
| A date is coming up | A chosen number of days before a date column. Each date fires once |

| Then | |
| --- | --- |
| Ask for approval or a review | The person who caused the change is the one asking, and is left out of the approvers |
| Send an email | To named people or groups, who created it, who last changed it, or a person column |
| Set a column | Not person or lookup columns |
| Move to a folder | In the same library. The folder is created if needed |
| Add an item to a list | In the same site |

Subjects and text can use `{title}`, `{by}` and `{link}`.

**What happened** shows each run: done, skipped because conditions were not met, or failed and why.

How rules stay safe:

- **They cannot loop.** Rules act as a special *Workflow* account, and its changes never trigger rules. It cannot sign in.
- **They cannot leak.** Email only goes to people in the directory who can already see the item.
- **They stay in their site.** Folders and lists are checked when the rule is saved and when it runs.
- **A failure is contained.** Each rule runs in its own transaction, so a failed rule undoes its own steps and the next
  rule still runs.

Rules run within seconds of a change. Date rules are checked every 10 minutes, using UTC dates.

## Backup and restore

Rules and the record of approvals travel with a site. A request that was still open is restored as cancelled, because
nobody is waiting on a copy.

## Known limits

- This is a rule engine, not the BPMN engine in the development plan. There are no branches, parallel paths, long-running
  processes or custom forms.
- SharePoint workflows are not migrated.
- Rules cannot set person or lookup columns, and they watch for any change, not a change to one column.
- Conditions are all-of. There is no "or".
- Readers can see an unapproved draft unless the library is set to [hide drafts](administration.md#who-can-see-drafts).
  In that case approvers need Contribute or higher.
- If a rule asks for approval from someone who cannot open the item, the run fails. It shows under What happened.
- Tested with Mailpit and a simulated farm of a few sites, not at production volume.

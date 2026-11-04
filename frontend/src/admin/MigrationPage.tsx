import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Badge,
  Button,
  Card,
  Checkbox,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Dropdown,
  Field,
  Input,
  MessageBar,
  MessageBarBody,
  Option,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
  Textarea,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import { AddRegular, ArrowSyncRegular, CopyRegular, DeleteRegular, EditRegular, PlugConnectedRegular, ScanRegular, SearchRegular } from "@fluentui/react-icons";
import { Link } from "react-router-dom";
import { api, routeFor } from "../api/client";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatDate, formatSize } from "../components/format";
import { JobDialog } from "./JobsPage";
import type { Job, MigrationSource, MigrationTask, ScanReport, SpAuth } from "./types";

const useStyles = makeStyles({
  section: { display: "block", margin: "20px 0 8px" },
  head: { display: "flex", alignItems: "center", justifyContent: "space-between", gap: "8px", flexWrap: "wrap" },
  card: { display: "flex", flexDirection: "column", gap: "6px", marginBottom: "10px" },
  row: { display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" },
  title: { flex: 1, minWidth: "220px", overflowWrap: "anywhere" },
  muted: { color: tokens.colorNeutralForeground3 },
  form: { display: "flex", flexDirection: "column", gap: "12px" },
});

const AUTH: { value: SpAuth; label: string; secret?: string; user?: boolean }[] = [
  { value: "NTLM", label: "Windows (NTLM)", secret: "Password", user: true },
  { value: "BASIC", label: "Basic", secret: "Password", user: true },
  { value: "COOKIE", label: "Cookie (AD FS farms)", secret: "Cookie header, for example FedAuth=…" },
  { value: "BEARER", label: "Bearer token", secret: "Token" },
  { value: "NONE", label: "None" },
];

const STATUS: Record<MigrationTask["status"], [string, "informative" | "brand" | "success" | "danger"]> = {
  NEW: ["Not scanned", "informative"], SCANNED: ["Scanned", "brand"], COPIED: ["Copied", "success"],
  SYNCED: ["In sync", "success"], FAILED: ["Needs attention", "danger"],
};

export function MigrationPage() {
  const s = useStyles();
  const qc = useQueryClient();
  const sources = useQuery({ queryKey: ["admin", "mig-sources"], queryFn: () => api<MigrationSource[]>("/api/v1/admin/migration/sources") });
  const tasks = useQuery({ queryKey: ["admin", "mig-tasks"], queryFn: () => api<MigrationTask[]>("/api/v1/admin/migration/tasks"), refetchInterval: 5000 });
  const [editing, setEditing] = useState<MigrationSource | "new" | null>(null);
  const [newTask, setNewTask] = useState<{ source?: string; web?: string } | null>(null);
  const [report, setReport] = useState<ScanReport | null>(null);
  const [job, setJob] = useState<string | null>(null);
  const [tests, setTests] = useState<Record<string, string>>({});
  const [found, setFound] = useState<{ source: MigrationSource; sites: { title: string; web: string }[] } | null>(null);
  const [error, setError] = useState<unknown>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ["admin"] });

  const run = async (fn: () => Promise<unknown>) => {
    setError(null);
    try {
      await fn();
      await refresh();
    } catch (e) {
      setError(e);
    }
  };
  const start = (url: string, body?: unknown) => run(async () => setJob((await api<Job>(url, { method: "POST", body })).id));

  return (
    <>
      <Text as="h1" size={700} weight="semibold">SharePoint migration</Text>
      <Text block className={s.muted} style={{ marginTop: 4 }}>
        Connect a farm, scan a site, copy it, then repeat the copy to bring over only what changed until you switch people over.
      </Text>
      <ErrorBar error={error ?? sources.error ?? tasks.error} />

      <div className={s.head}>
        <Text as="h2" size={500} weight="semibold" className={s.section}>SharePoint farms</Text>
        <Button icon={<AddRegular />} onClick={() => setEditing("new")}>Add farm</Button>
      </div>
      {sources.isPending ? <Loading /> : !sources.data?.length ? <Empty title="No farms connected yet" /> : sources.data.map((f) => (
        <Card key={f.id} className={s.card}>
          <div className={s.row}>
            <div className={s.title}>
              <Text weight="semibold" block>{f.name}</Text>
              <Text size={200} className={s.muted}>{f.baseUrl} · {AUTH.find((a) => a.value === f.auth)?.label}{f.username ? ` · ${f.domain ? f.domain + "\\" : ""}${f.username}` : ""}</Text>
            </div>
            <Button size="small" icon={<PlugConnectedRegular />} onClick={() => run(async () => {
              const r = await api<{ ok: boolean; title?: string; error?: string }>(`/api/v1/admin/migration/sources/${f.id}/test`, { method: "POST" });
              setTests((t) => ({ ...t, [f.id]: r.ok ? `Connected to "${r.title}"` : r.error ?? "Connection failed" }));
            })}>Test</Button>
            <Button size="small" icon={<SearchRegular />} onClick={() => run(async () => {
              const sites = await api<{ title: string; web: string }[]>(`/api/v1/admin/migration/sources/${f.id}/discover`, { method: "POST" });
              setFound({ source: f, sites });
            })}>Find sites</Button>
            <Button size="small" icon={<AddRegular />} onClick={() => setNewTask({ source: f.id })}>New task</Button>
            <Button size="small" appearance="subtle" aria-label="Edit farm" icon={<EditRegular />} onClick={() => setEditing(f)} />
            <Button size="small" appearance="subtle" aria-label="Delete farm" icon={<DeleteRegular />} onClick={() => {
              if (window.confirm(`Remove ${f.name}?`)) void run(() => api(`/api/v1/admin/migration/sources/${f.id}`, { method: "DELETE" }));
            }} />
          </div>
          {tests[f.id] && <Text size={200} aria-live="polite">{tests[f.id]}</Text>}
        </Card>
      ))}

      <Text as="h2" size={500} weight="semibold" className={s.section}>Migration tasks</Text>
      {tasks.isPending ? <Loading /> : !tasks.data?.length ? <Empty title="No tasks yet" /> : tasks.data.map((t) => {
        const farm = sources.data?.find((f) => f.id === t.sourceId);
        const [label, color] = STATUS[t.status];
        return (
          <Card key={t.id} className={s.card}>
            <div className={s.row}>
              <div className={s.title}>
                <Text weight="semibold" block>{farm?.name} {t.sourceWeb} → <Link to={routeFor("/sites/" + t.targetSite)}>/sites/{t.targetSite}</Link></Text>
                <Text size={200} className={s.muted}>
                  {t.items} items mapped{t.lastSyncAt ? ` · last copied ${formatDate(t.lastSyncAt)}` : ""}
                </Text>
              </div>
              <Badge appearance="tint" color={color}>{label}</Badge>
            </div>
            <div className={s.row}>
              <Button size="small" icon={<ScanRegular />} onClick={() => start(`/api/v1/admin/migration/tasks/${t.id}/scan`, { deep: false })}>Quick scan</Button>
              <Button size="small" icon={<ScanRegular />} onClick={() => start(`/api/v1/admin/migration/tasks/${t.id}/scan`, { deep: true })}>Full scan</Button>
              {t.scan && <Button size="small" appearance="subtle" onClick={() => setReport(t.scan!)}>View report</Button>}
              <Button size="small" appearance="primary" icon={t.lastSyncAt ? <ArrowSyncRegular /> : <CopyRegular />}
                onClick={() => start(`/api/v1/admin/migration/tasks/${t.id}/copy`)}>
                {t.lastSyncAt ? "Sync changes" : "Start copy"}
              </Button>
              {t.lastJobId && <Button size="small" appearance="subtle" onClick={() => setJob(t.lastJobId!)}>Last run</Button>}
              <Button size="small" appearance="subtle" aria-label="Delete task" icon={<DeleteRegular />} onClick={() => {
                if (window.confirm("Delete this task? Copied content stays, but delta sync and redirects from old SharePoint links for this site stop working.")) {
                  void run(() => api(`/api/v1/admin/migration/tasks/${t.id}`, { method: "DELETE" }));
                }
              }} />
            </div>
          </Card>
        );
      })}

      <RedirectTester />

      {editing && <SourceDialog source={editing === "new" ? null : editing} onClose={() => setEditing(null)} onSaved={refresh} />}
      {newTask && sources.data && <TaskDialog sources={sources.data} initial={newTask} onClose={() => setNewTask(null)} onSaved={refresh} />}
      {report && <ReportDialog report={report} onClose={() => setReport(null)} />}
      {job && <JobDialog id={job} onClose={() => { setJob(null); void refresh(); }} />}
      {found && (
        <Dialog open onOpenChange={(_, d) => !d.open && setFound(null)}>
          <DialogSurface style={{ maxWidth: 560 }}>
            <DialogBody>
              <DialogTitle>Sites in {found.source.name}</DialogTitle>
              <DialogContent>
                {!found.sites.length ? <Text>SharePoint search found no sites. Enter the site's address in a new task instead.</Text> : found.sites.map((x) => (
                  <div key={x.web} className={s.row} style={{ padding: "4px 0" }}>
                    <div className={s.title}><Text block>{x.title}</Text><Text size={200} className={s.muted}>{x.web}</Text></div>
                    <Button size="small" onClick={() => { setNewTask({ source: found.source.id, web: x.web }); setFound(null); }}>New task</Button>
                  </div>
                ))}
              </DialogContent>
              <DialogActions><Button onClick={() => setFound(null)}>Close</Button></DialogActions>
            </DialogBody>
          </DialogSurface>
        </Dialog>
      )}
    </>
  );
}

function SourceDialog({ source, onClose, onSaved }: { source: MigrationSource | null; onClose: () => void; onSaved: () => unknown }) {
  const s = useStyles();
  const [name, setName] = useState(source?.name ?? "");
  const [baseUrl, setBaseUrl] = useState(source?.baseUrl ?? "");
  const [auth, setAuth] = useState<SpAuth>(source?.auth ?? "NTLM");
  const [username, setUsername] = useState(source?.username ?? "");
  const [domain, setDomain] = useState(source?.domain ?? "");
  const [secret, setSecret] = useState("");
  const [error, setError] = useState<unknown>(null);
  const a = AUTH.find((x) => x.value === auth)!;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api(`/api/v1/admin/migration/sources${source ? "/" + source.id : ""}`, {
        method: source ? "PUT" : "POST",
        body: { name, baseUrl, auth, username, domain, secret },
      });
      await onSaved();
      onClose();
    } catch (err) {
      setError(err);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 480 }}>
        <form onSubmit={submit}>
          <DialogBody>
            <DialogTitle>{source ? "Edit farm" : "Add a SharePoint farm"}</DialogTitle>
            <DialogContent className={s.form}>
              <ErrorBar error={error} />
              <Field label="Name" required><Input value={name} onChange={(_, d) => setName(d.value)} autoFocus /></Field>
              <Field label="Web application address" required hint="For example https://sharepoint.contoso.com">
                <Input value={baseUrl} onChange={(_, d) => setBaseUrl(d.value)} />
              </Field>
              <Field label="Sign-in">
                <Dropdown value={a.label} selectedOptions={[auth]} onOptionSelect={(_, d) => setAuth(d.optionValue as SpAuth)}>
                  {AUTH.map((x) => <Option key={x.value} value={x.value}>{x.label}</Option>)}
                </Dropdown>
              </Field>
              {a.user && (
                <>
                  <Field label="Domain"><Input value={domain} onChange={(_, d) => setDomain(d.value)} /></Field>
                  <Field label="User name" hint="Needs Site Collection Administrator or Full Read on the sites you migrate">
                    <Input value={username} onChange={(_, d) => setUsername(d.value)} />
                  </Field>
                </>
              )}
              {a.secret && (
                <Field label={a.secret} hint={source?.hasSecret ? "Leave empty to keep the saved one" : "Stored encrypted and never shown again"}>
                  <Input type="password" autoComplete="new-password" value={secret} onChange={(_, d) => setSecret(d.value)} />
                </Field>
              )}
              {baseUrl.toLowerCase().startsWith("http://") && (auth === "BASIC" || auth === "BEARER" || auth === "COOKIE") && (
                <MessageBar intent="warning"><MessageBarBody>This sends the credential unencrypted. Use an https:// address.</MessageBarBody></MessageBar>
              )}
            </DialogContent>
            <DialogActions>
              <Button onClick={onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={!name.trim() || !baseUrl.trim()}>Save</Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}

function TaskDialog({ sources, initial, onClose, onSaved }: {
  sources: MigrationSource[]; initial: { source?: string; web?: string }; onClose: () => void; onSaved: () => unknown;
}) {
  const s = useStyles();
  const [sourceId, setSourceId] = useState(initial.source ?? sources[0]?.id);
  const [web, setWeb] = useState(initial.web ?? "");
  const [target, setTarget] = useState((initial.web ?? "").split("/").filter(Boolean).pop()?.toLowerCase().replace(/[^a-z0-9-]+/g, "-") ?? "");
  const [versions, setVersions] = useState("all");
  const [permissions, setPermissions] = useState(true);
  const [metadata, setMetadata] = useState(true);
  const [deleteMissing, setDeleteMissing] = useState(false);
  const [mappings, setMappings] = useState("");
  const [error, setError] = useState<unknown>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const userMappings: Record<string, string> = {};
      for (const line of mappings.split("\n")) {
        const [from, to] = line.split("=").map((x) => x.trim());
        if (from && to) userMappings[from] = to;
      }
      await api("/api/v1/admin/migration/tasks", {
        method: "POST",
        body: {
          sourceId, sourceWeb: web, targetSite: target,
          options: { versions: /^\d+$/.test(versions) ? Number(versions) : versions, permissions, metadata, deleteMissing, userMappings },
        },
      });
      await onSaved();
      onClose();
    } catch (err) {
      setError(err);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 520 }}>
        <form onSubmit={submit}>
          <DialogBody>
            <DialogTitle>New migration task</DialogTitle>
            <DialogContent className={s.form}>
              <ErrorBar error={error} />
              <Field label="Farm" required>
                <Dropdown value={sources.find((f) => f.id === sourceId)?.name ?? ""} selectedOptions={sourceId ? [sourceId] : []}
                  onOptionSelect={(_, d) => setSourceId(d.optionValue ?? sourceId)}>
                  {sources.map((f) => <Option key={f.id} value={f.id}>{f.name}</Option>)}
                </Dropdown>
              </Field>
              <Field label="SharePoint site" required hint="Its address, for example /sites/hr">
                <Input value={web} onChange={(_, d) => setWeb(d.value)} />
              </Field>
              <Field label="NEXORA site URL name" required hint="Created if it doesn't exist">
                <Input contentBefore="/sites/" value={target} onChange={(_, d) => setTarget(d.value.toLowerCase())} />
              </Field>
              <Field label="Version history">
                <Dropdown value={versions === "all" ? "All versions" : versions === "none" ? "Current version only" : `Last ${versions} and current`}
                  selectedOptions={[versions]} onOptionSelect={(_, d) => setVersions(d.optionValue ?? "all")}>
                  <Option value="all">All versions</Option>
                  <Option value="10">Last 10 and current</Option>
                  <Option value="5">Last 5 and current</Option>
                  <Option value="none">Current version only</Option>
                </Dropdown>
              </Field>
              <Checkbox label="Copy permissions and groups" checked={permissions} onChange={(_, d) => setPermissions(!!d.checked)} />
              {!permissions && (
                <MessageBar intent="warning"><MessageBarBody>Everything will follow the new site's permissions, so content restricted in SharePoint will be open to site members.</MessageBarBody></MessageBar>
              )}
              <Checkbox label="Copy columns and their values" checked={metadata} onChange={(_, d) => setMetadata(!!d.checked)} />
              <Checkbox label="On sync, move items deleted in SharePoint to the recycle bin" checked={deleteMissing} onChange={(_, d) => setDeleteMissing(!!d.checked)} />
              <Field label="User mappings (optional)" hint="One per line: contoso\jdoe = john.doe. Needed when accounts have different names here.">
                <Textarea value={mappings} onChange={(_, d) => setMappings(d.value)} />
              </Field>
            </DialogContent>
            <DialogActions>
              <Button onClick={onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={!web.trim() || !target.trim() || !sourceId}>Create</Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}

function ReportDialog({ report, onClose }: { report: ScanReport; onClose: () => void }) {
  const s = useStyles();
  const t = report.totals;
  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 820 }}>
        <DialogBody>
          <DialogTitle>Scan of {report.web.title}</DialogTitle>
          <DialogContent className={s.form}>
            <Text size={200} className={s.muted}>
              {report.web.url} · {report.deep ? "Full scan" : "Quick scan"} · {formatDate(report.scannedAt)}
            </Text>
            <Text>
              {t.libraries} libraries, {t.lists} lists, {t.items} items
              {report.deep ? `, ${t.files} files, ${formatSize(t.bytes)}` : ""}, {t.uniquePermissions} with their own permissions
            </Text>
            {report.warnings.map((w) => <MessageBar key={w} intent="warning"><MessageBarBody>{w}</MessageBarBody></MessageBar>)}
            <div style={{ overflowX: "auto" }}>
              <Table aria-label="Lists and libraries" size="small" style={{ minWidth: 560 }}>
                <TableHeader>
                  <TableRow>
                    <TableHeaderCell>Name</TableHeaderCell>
                    <TableHeaderCell style={{ width: 110 }}>Type</TableHeaderCell>
                    <TableHeaderCell style={{ width: 80 }}>Items</TableHeaderCell>
                    <TableHeaderCell style={{ width: 200 }}>Notes</TableHeaderCell>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {report.lists.map((l) => (
                    <TableRow key={l.id}>
                      <TableCell>{l.title}</TableCell>
                      <TableCell>{l.kind === "library" ? "Library" : l.kind === "list" ? "List" : "Skipped"}</TableCell>
                      <TableCell>{l.itemCount}</TableCell>
                      <TableCell>{[l.note, l.uniquePermissions ? "Own permissions" : null].filter(Boolean).join(" · ")}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            {report.issues.length > 0 && (
              <>
                <Text weight="semibold">{report.issues.length} file issue{report.issues.length === 1 ? "" : "s"}</Text>
                {report.issues.map((i) => <Text key={i.url + i.issue} size={200} block style={{ overflowWrap: "anywhere" }}>{i.url}: {i.issue}</Text>)}
              </>
            )}
          </DialogContent>
          <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

/** Checks where an old SharePoint link goes now. */
function RedirectTester() {
  const s = useStyles();
  const [url, setUrl] = useState("");
  const [result, setResult] = useState<string | null>(null);
  return (
    <>
      <Text as="h2" size={500} weight="semibold" className={s.section}>Test an old link</Text>
      <Text block size={200} className={s.muted} style={{ marginBottom: 6 }}>
        After switching over, point the old SharePoint host name at NEXORA and old links open the new location.
      </Text>
      <form className={s.row} onSubmit={async (e) => {
        e.preventDefault();
        try {
          const r = await api<{ path: string }>(`/api/v1/redirects/resolve?url=${encodeURIComponent(url.trim())}`);
          setResult(`Goes to ${r.path}`);
        } catch {
          setResult("Not found: this link doesn't match anything migrated.");
        }
      }}>
        <Input aria-label="Old SharePoint link" style={{ flex: 1, minWidth: 260 }} placeholder="https://sharepoint.contoso.com/sites/hr/Shared Documents/a.docx"
          value={url} onChange={(_, d) => setUrl(d.value)} />
        <Button type="submit" disabled={!url.trim()}>Test</Button>
      </form>
      {result && <Text block style={{ marginTop: 6, overflowWrap: "anywhere" }} aria-live="polite">{result}</Text>}
    </>
  );
}

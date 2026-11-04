import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Badge,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Field,
  Input,
  MessageBar,
  MessageBarBody,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
  Toolbar,
  ToolbarButton,
  tokens,
} from "@fluentui/react-components";
import { ArrowDownloadRegular, ArrowUploadRegular, DatabaseArrowUpRegular, DeleteRegular, HistoryRegular } from "@fluentui/react-icons";
import { api, postFile } from "../api/client";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatDate, formatSize } from "../components/format";
import { JobDialog } from "./JobsPage";
import type { Backup, Job } from "./types";

const KINDS = { SITE: "Site", FARM: "Farm", UPLOAD: "Imported" } as const;

export function BackupsPage() {
  const qc = useQueryClient();
  const backups = useQuery({
    queryKey: ["admin", "backups"],
    queryFn: () => api<Backup[]>("/api/v1/admin/backups"),
    refetchInterval: 10000,
  });
  const [error, setError] = useState<unknown>(null);
  const [job, setJob] = useState<string | null>(null);
  const [restoring, setRestoring] = useState<Backup | null>(null);
  const [uploading, setUploading] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  const farm = async () => {
    setError(null);
    try {
      setJob((await api<Job>("/api/v1/admin/backups/farm", { method: "POST" })).id);
    } catch (e) {
      setError(e);
    }
  };
  const remove = async (b: Backup) => {
    if (!window.confirm(`Delete the backup "${b.label}" from ${formatDate(b.createdAt)}? This cannot be undone.`)) return;
    setError(null);
    try {
      await api(`/api/v1/admin/backups/${b.id}`, { method: "DELETE" });
      await qc.invalidateQueries({ queryKey: ["admin", "backups"] });
    } catch (e) {
      setError(e);
    }
  };
  const upload = async (file: File) => {
    setUploading(true);
    setError(null);
    try {
      setRestoring(await postFile<Backup>("/api/v1/admin/import", file));
      await qc.invalidateQueries({ queryKey: ["admin", "backups"] });
    } catch (e) {
      setError(e);
    } finally {
      setUploading(false);
    }
  };

  return (
    <>
      <Text as="h1" size={700} weight="semibold">Backup and restore</Text>
      <Text block style={{ margin: "4px 0 8px", color: tokens.colorNeutralForeground3 }}>
        Back up one site from the Sites page, or the whole farm here. Export a site by downloading its package, and import it into this or another farm.
      </Text>
      <Toolbar style={{ paddingLeft: 0, flexWrap: "wrap" }} aria-label="Backup commands">
        <ToolbarButton appearance="primary" icon={<DatabaseArrowUpRegular />} onClick={farm}>Back up the farm</ToolbarButton>
        <ToolbarButton icon={<ArrowUploadRegular />} disabled={uploading} onClick={() => input.current?.click()}>
          {uploading ? "Uploading…" : "Import a site package"}
        </ToolbarButton>
        <input ref={input} type="file" accept=".zip" hidden onChange={(e) => {
          const f = e.target.files?.[0];
          e.target.value = "";
          if (f) void upload(f);
        }} />
      </Toolbar>
      <MessageBar intent="warning" style={{ margin: "8px 0 12px" }}>
        <MessageBarBody>
          Farm backups contain every file and local account password hashes. Keep the backup folder as private as the database, and copy it off this server.
        </MessageBarBody>
      </MessageBar>
      <ErrorBar error={error ?? backups.error} />
      {backups.isPending ? <Loading /> : !backups.data?.length ? <Empty title="No backups yet" /> : (
        <div style={{ overflowX: "auto" }}>
          <Table aria-label="Backups" style={{ minWidth: 720 }}>
            <TableHeader>
              <TableRow>
                <TableHeaderCell>Backup</TableHeaderCell>
                <TableHeaderCell style={{ width: 90 }}>Type</TableHeaderCell>
                <TableHeaderCell style={{ width: 90 }}>Size</TableHeaderCell>
                <TableHeaderCell style={{ width: 170 }}>Created</TableHeaderCell>
                <TableHeaderCell style={{ width: 160 }} aria-label="Actions" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {backups.data.map((b) => (
                <TableRow key={b.id}>
                  <TableCell>
                    {b.label}
                    <Text block size={200} style={{ color: tokens.colorNeutralForeground3 }}>
                      {b.sites.length} site{b.sites.length === 1 ? "" : "s"}{b.includesDirectory ? ", users and groups" : ""}
                    </Text>
                  </TableCell>
                  <TableCell><Badge appearance="tint">{KINDS[b.kind]}</Badge></TableCell>
                  <TableCell>{formatSize(b.sizeBytes)}</TableCell>
                  <TableCell>{formatDate(b.createdAt)} by {b.createdBy}</TableCell>
                  <TableCell>
                    <Button size="small" icon={<HistoryRegular />} onClick={() => setRestoring(b)}>Restore</Button>{" "}
                    {b.sites.length === 1 && (
                      <Button as="a" size="small" appearance="subtle" aria-label="Download package" icon={<ArrowDownloadRegular />}
                        href={`/api/v1/admin/backups/${b.id}/sites/${b.sites[0].name}/package`} />
                    )}
                    <Button size="small" appearance="subtle" aria-label="Delete backup" icon={<DeleteRegular />} onClick={() => remove(b)} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {restoring && <RestoreDialog backup={restoring} onClose={() => setRestoring(null)} onStarted={(id) => { setRestoring(null); setJob(id); }} />}
      {job && <JobDialog id={job} onClose={() => { setJob(null); void qc.invalidateQueries({ queryKey: ["admin"] }); }} />}
    </>
  );
}

function RestoreDialog({ backup, onClose, onStarted }: { backup: Backup; onClose: () => void; onStarted: (jobId: string) => void }) {
  const [chosen, setChosen] = useState<Record<string, string>>(() =>
    backup.sites.length === 1 ? { [backup.sites[0].name]: backup.sites[0].name } : {});
  const [directory, setDirectory] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const names = Object.keys(chosen);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const job = await api<Job>(`/api/v1/admin/backups/${backup.id}/restore`, {
        method: "POST",
        body: { directory, sites: names.map((name) => ({ name, targetName: chosen[name].trim() })) },
      });
      onStarted(job.id);
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 560 }}>
        <DialogBody>
          <DialogTitle>Restore</DialogTitle>
          <DialogContent style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            <ErrorBar error={error} />
            <Text>A restore creates a new site and never overwrites one. To put a site back at its original URL, purge it from the recycle bin first, or restore it under another URL.</Text>
            {backup.includesDirectory && (
              <Checkbox label="Add missing users, groups and memberships (existing accounts are not changed)" checked={directory} onChange={(_, d) => setDirectory(!!d.checked)} />
            )}
            {backup.sites.map((site) => {
              const on = site.name in chosen;
              return (
                <div key={site.name} style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
                  <Checkbox checked={on} label={`${site.title} (${site.items} items, ${formatSize(site.sizeBytes)})`} style={{ flex: 1, minWidth: 220 }}
                    onChange={(_, d) => setChosen((c) => {
                      const next = { ...c };
                      if (d.checked) next[site.name] = site.name;
                      else delete next[site.name];
                      return next;
                    })} />
                  {on && (
                    <Field style={{ flex: 1, minWidth: 180 }}>
                      <Input aria-label={`New URL name for ${site.title}`} contentBefore="/sites/" value={chosen[site.name]}
                        onChange={(_, d) => setChosen((c) => ({ ...c, [site.name]: d.value.toLowerCase() }))} />
                    </Field>
                  )}
                </div>
              );
            })}
          </DialogContent>
          <DialogActions>
            <Button onClick={onClose}>Cancel</Button>
            <Button appearance="primary" disabled={busy || (!names.length && !directory)} onClick={submit}>Restore</Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

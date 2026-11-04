import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Badge,
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Dropdown,
  Field,
  Input,
  Option,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
  Toolbar,
  ToolbarButton,
  Tooltip,
  tokens,
} from "@fluentui/react-components";
import { AddRegular, DatabaseArrowUpRegular, DeleteRegular } from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { Templates } from "../api/types";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatRelative, formatSize } from "../components/format";
import type { Job, SiteRow } from "./types";

export function SitesAdminPage() {
  const qc = useQueryClient();
  const sites = useQuery({ queryKey: ["admin", "sites"], queryFn: () => api<SiteRow[]>("/api/v1/admin/sites") });
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const backup = async (site: SiteRow) => {
    setError(null);
    try {
      await api<Job>(`/api/v1/admin/sites/${site.id}/backup`, { method: "POST" });
      setNotice(`Backing up ${site.path}. Follow it under Jobs; it will appear in Backup and restore.`);
    } catch (e) {
      setError(e);
    }
  };
  const setHub = async (site: SiteRow, enabled: boolean) => {
    setError(null);
    try {
      await api(`/api/v1/admin/sites/${site.id}/hub`, { method: "PUT", body: { enabled } });
      await Promise.all([
        qc.invalidateQueries({ queryKey: ["admin", "sites"] }),
        qc.invalidateQueries({ queryKey: ["design"] }),
        qc.invalidateQueries({ queryKey: ["hubs"] }),
      ]);
    } catch (e) {
      setError(e);
    }
  };
  const remove = async (site: SiteRow) => {
    if (!window.confirm(`Delete ${site.path}? It goes to the recycle bin and can be restored from there.`)) return;
    setError(null);
    try {
      await api(`/api/v1/admin/sites/${site.id}`, { method: "DELETE" });
      await qc.invalidateQueries({ queryKey: ["admin", "sites"] });
    } catch (e) {
      setError(e);
    }
  };

  return (
    <>
      <Text as="h1" size={700} weight="semibold">Sites</Text>
      <Toolbar style={{ paddingLeft: 0 }} aria-label="Site commands">
        <ToolbarButton appearance="primary" icon={<AddRegular />} onClick={() => setCreating(true)}>Create site</ToolbarButton>
        <Link to="/recycle-bin"><ToolbarButton icon={<DeleteRegular />}>Deleted sites</ToolbarButton></Link>
      </Toolbar>
      <ErrorBar error={error ?? sites.error} />
      {notice && <Text block style={{ margin: "4px 0 10px", color: tokens.colorNeutralForeground2 }} aria-live="polite">{notice}</Text>}
      {sites.isPending ? <Loading /> : !sites.data?.length ? <Empty title="No sites yet" /> : (
        <div style={{ overflowX: "auto" }}>
          <Table aria-label="Sites" style={{ minWidth: 760 }}>
            <TableHeader>
              <TableRow>
                <TableHeaderCell>Site</TableHeaderCell>
                <TableHeaderCell>Owners</TableHeaderCell>
                <TableHeaderCell style={{ width: 80 }}>Hub</TableHeaderCell>
                <TableHeaderCell style={{ width: 80 }}>Files</TableHeaderCell>
                <TableHeaderCell style={{ width: 100 }}>Size</TableHeaderCell>
                <TableHeaderCell style={{ width: 130 }}>Last change</TableHeaderCell>
                <TableHeaderCell style={{ width: 90 }} aria-label="Actions" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {sites.data.map((site) => (
                <TableRow key={site.id}>
                  <TableCell>
                    <Link to={routeFor(site.path)}>{site.title}</Link>
                    {site.subsite && <Badge appearance="outline" size="small" style={{ marginLeft: 6 }}>Subsite</Badge>}
                    <Text block size={200} style={{ color: tokens.colorNeutralForeground3 }}>{site.path}</Text>
                  </TableCell>
                  <TableCell>{site.owners.join(", ") || "None"}</TableCell>
                  <TableCell>
                    {!site.subsite && (
                      <Switch checked={site.hub} aria-label={`${site.title} is a hub site`} onChange={(_, d) => setHub(site, d.checked)} />
                    )}
                  </TableCell>
                  <TableCell>{site.files}</TableCell>
                  <TableCell>{formatSize(site.bytes)}</TableCell>
                  <TableCell>{formatRelative(site.lastModified)}</TableCell>
                  <TableCell>
                    <Tooltip content="Back up this site" relationship="label">
                      <Button size="small" appearance="subtle" icon={<DatabaseArrowUpRegular />} onClick={() => backup(site)} />
                    </Tooltip>
                    <Tooltip content="Delete this site" relationship="label">
                      <Button size="small" appearance="subtle" icon={<DeleteRegular />} onClick={() => remove(site)} />
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {creating && <CreateSiteDialog onClose={() => setCreating(false)} />}
    </>
  );
}

function CreateSiteDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const templates = useQuery({ queryKey: ["templates"], queryFn: () => api<Templates>("/api/v1/templates"), staleTime: Infinity });
  const [url, setUrl] = useState("");
  const [title, setTitle] = useState("");
  const [owner, setOwner] = useState("");
  const [template, setTemplate] = useState("TEAM");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const chosen = templates.data?.sites.find((t) => t.id === template);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api("/api/v1/admin/sites", { method: "POST", body: { url: url.trim(), title: title.trim(), template, owner: owner.trim() || undefined } });
      await qc.invalidateQueries({ queryKey: ["admin", "sites"] });
      onClose();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 480 }}>
        <form onSubmit={submit}>
          <DialogBody>
            <DialogTitle>Create site</DialogTitle>
            <DialogContent style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <ErrorBar error={error} />
              <Field label="Title"><Input value={title} onChange={(_, d) => setTitle(d.value)} autoFocus /></Field>
              <Field label="URL name" required hint={`/sites/${url || "name"}`}>
                <Input value={url} onChange={(_, d) => setUrl(d.value.toLowerCase())} />
              </Field>
              <Field label="Template" hint={chosen?.description}>
                <Dropdown value={chosen?.title ?? ""} selectedOptions={[template]} onOptionSelect={(_, d) => setTemplate(d.optionValue ?? "TEAM")}>
                  {templates.data?.sites.map((t) => <Option key={t.id} value={t.id}>{t.title}</Option>)}
                </Dropdown>
              </Field>
              <Field label="Owner's user name" hint="Leave empty to own the site yourself">
                <Input value={owner} onChange={(_, d) => setOwner(d.value)} />
              </Field>
            </DialogContent>
            <DialogActions>
              <Button onClick={onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={busy || !url.trim()}>{busy ? "Creating…" : "Create"}</Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}

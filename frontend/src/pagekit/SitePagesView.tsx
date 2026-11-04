import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
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
  Field,
  Input,
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
  makeStyles,
  mergeClasses,
  tokens,
} from "@fluentui/react-components";
import { DeleteRegular, DocumentAddRegular, DocumentOnePageRegular, HomeRegular, NewsRegular } from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { NodeDto, PageDetail, PageKind, PageSummary, PageTemplateInfo } from "../api/types";
import { can } from "../components/me";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatRelative } from "../components/format";

const useStyles = makeStyles({
  title: { display: "flex", alignItems: "center", gap: "8px", textDecoration: "none", color: tokens.colorNeutralForeground1 },
  muted: { color: tokens.colorNeutralForeground3 },
  wrap: { overflowX: "auto" },
  gallery: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(170px, 1fr))", gap: "10px" },
  tileWrap: { position: "relative", display: "flex" },
  tile: {
    display: "flex", flexDirection: "column", gap: "4px", flex: 1, textAlign: "left", padding: "10px", font: "inherit",
    cursor: "pointer", borderRadius: tokens.borderRadiusLarge, border: `2px solid ${tokens.colorNeutralStroke2}`,
    backgroundColor: tokens.colorNeutralBackground1, color: tokens.colorNeutralForeground1,
  },
  tileOn: { border: `2px solid ${tokens.colorBrandStroke1}` },
  tileDelete: { position: "absolute", top: "4px", right: "4px" },
});

/** Creates a page or news post from a template and opens it in the editor. */
export function NewPageDialog({ site, kind, onClose }: { site: NodeDto; kind: PageKind; onClose: () => void }) {
  const s = useStyles();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const templates = useQuery({
    queryKey: ["page-templates", site.id],
    queryFn: () => api<PageTemplateInfo[]>(`/api/v1/sites/${site.id}/page-templates`),
  });
  const [title, setTitle] = useState("");
  const [template, setTemplate] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const choices: PageTemplateInfo[] = [{ id: "", title: "Blank", description: "Start with an empty page", custom: false }, ...(templates.data ?? [])];

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = await api<PageDetail>(`/api/v1/sites/${site.id}/pages`, {
        method: "POST",
        body: { title: title.trim(), kind, template: template || undefined },
      });
      navigate(routeFor(created.page.url) + "?mode=edit");
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };

  const remove = async (t: PageTemplateInfo) => {
    if (!window.confirm(`Delete the template ${t.title}? Pages made from it stay as they are.`)) return;
    setError(null);
    try {
      await api(`/api/v1/pages/${t.id}`, { method: "DELETE" });
      if (template === t.id) setTemplate("");
      await qc.invalidateQueries({ queryKey: ["page-templates", site.id] });
    } catch (err) {
      setError(err);
    }
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 640 }}>
        <form onSubmit={submit}>
          <DialogBody>
            <DialogTitle>{kind === "NEWS" ? "New news post" : "New page"}</DialogTitle>
            <DialogContent style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <ErrorBar error={error ?? templates.error} />
              <Field label={kind === "NEWS" ? "Headline" : "Page title"} required>
                <Input value={title} onChange={(_, d) => setTitle(d.value)} autoFocus />
              </Field>
              <div className={s.gallery} role="radiogroup" aria-label="Template">
                {choices.map((t) => (
                  <div key={t.id} className={s.tileWrap}>
                    <button type="button" role="radio" aria-checked={template === t.id}
                      className={mergeClasses(s.tile, template === t.id && s.tileOn)} onClick={() => setTemplate(t.id)}>
                      <Text weight="semibold">{t.title}</Text>
                      <Text size={200} className={s.muted}>{t.description || (t.custom ? "Saved in this site" : "")}</Text>
                    </button>
                    {t.custom && (
                      <Tooltip content={`Delete template ${t.title}`} relationship="label">
                        <Button className={s.tileDelete} size="small" appearance="subtle" icon={<DeleteRegular />} onClick={() => remove(t)} />
                      </Tooltip>
                    )}
                  </div>
                ))}
              </div>
            </DialogContent>
            <DialogActions>
              <Button onClick={onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={busy || !title.trim()}>{busy ? "Creating…" : "Create"}</Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}

/** /sites/<site>/SitePages: every page and news post in the site. */
export function SitePagesView({ site }: { site: NodeDto }) {
  const s = useStyles();
  const pages = useQuery({ queryKey: ["pages", site.id], queryFn: () => api<PageSummary[]>(`/api/v1/sites/${site.id}/pages`) });
  const [creating, setCreating] = useState<PageKind | null>(null);
  const sorted = [...(pages.data ?? [])].sort((a, b) => b.modifiedAt.localeCompare(a.modifiedAt));

  return (
    <>
      <Toolbar aria-label="Pages commands" style={{ paddingLeft: 0, flexWrap: "wrap" }}>
        {can(site, "ADD_ITEMS") && (
          <>
            <ToolbarButton appearance="primary" icon={<DocumentAddRegular />} onClick={() => setCreating("PAGE")}>New page</ToolbarButton>
            <ToolbarButton icon={<NewsRegular />} onClick={() => setCreating("NEWS")}>New news post</ToolbarButton>
          </>
        )}
      </Toolbar>
      <Text as="h2" size={600} weight="semibold" style={{ display: "block", margin: "8px 0 12px" }}>Site pages</Text>
      <ErrorBar error={pages.error} />
      {pages.isPending ? <Loading /> : !sorted.length ? (
        <Empty title="No pages yet">
          {can(site, "ADD_ITEMS") ? <Text>Create a page or a news post to get started.</Text> : null}
        </Empty>
      ) : (
        <div className={s.wrap}>
          <Table aria-label="Site pages" style={{ minWidth: 560 }}>
            <TableHeader>
              <TableRow>
                <TableHeaderCell>Title</TableHeaderCell>
                <TableHeaderCell style={{ width: 110 }}>Type</TableHeaderCell>
                <TableHeaderCell style={{ width: 190 }}>Status</TableHeaderCell>
                <TableHeaderCell style={{ width: 190 }}>Modified</TableHeaderCell>
              </TableRow>
            </TableHeader>
            <TableBody>
              {sorted.map((p) => (
                <TableRow key={p.id}>
                  <TableCell>
                    <Link to={routeFor(p.url)} className={s.title}>
                      {p.kind === "NEWS" ? <NewsRegular /> : <DocumentOnePageRegular />}
                      <Text weight="semibold">{p.title}</Text>
                      {p.home && <Badge appearance="tint" icon={<HomeRegular />}>Home</Badge>}
                    </Link>
                  </TableCell>
                  <TableCell>{p.kind === "NEWS" ? "News" : "Page"}</TableCell>
                  <TableCell>
                    {!p.published ? <Badge color="warning" appearance="tint">Draft</Badge>
                      : p.hasDraft ? <Badge color="informative" appearance="tint">Published, edits pending</Badge>
                      : <Badge color="success" appearance="tint">Published {p.version}.0</Badge>}
                  </TableCell>
                  <TableCell>
                    <Text size={200} className={s.muted}>{formatRelative(p.modifiedAt)} by {p.modifiedBy?.displayName}</Text>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {creating && <NewPageDialog site={site} kind={creating} onClose={() => setCreating(null)} />}
    </>
  );
}

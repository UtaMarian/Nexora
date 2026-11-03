import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
  Toolbar,
  ToolbarButton,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import { DocumentAddRegular, DocumentOnePageRegular, HomeRegular, NewsRegular } from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { NodeDto, PageDetail, PageKind, PageSummary } from "../api/types";
import { can } from "../components/me";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatRelative } from "../components/format";
import { PromptDialog } from "../dialogs/PromptDialog";

const useStyles = makeStyles({
  title: { display: "flex", alignItems: "center", gap: "8px", textDecoration: "none", color: tokens.colorNeutralForeground1 },
  muted: { color: tokens.colorNeutralForeground3 },
  wrap: { overflowX: "auto" },
});

/** Creates a page or news post and opens it in the editor. */
export function NewPageDialog({ site, kind, onClose }: { site: NodeDto; kind: PageKind; onClose: () => void }) {
  const navigate = useNavigate();
  return (
    <PromptDialog
      title={kind === "NEWS" ? "New news post" : "New page"}
      label={kind === "NEWS" ? "Headline" : "Page title"}
      submitLabel="Create"
      onClose={onClose}
      onSubmit={async (title) => {
        const created = await api<PageDetail>(`/api/v1/sites/${site.id}/pages`, { method: "POST", body: { title, kind } });
        navigate(routeFor(created.page.url) + "?mode=edit");
      }}
    />
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

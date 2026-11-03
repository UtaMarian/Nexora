import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Card,
  CardHeader,
  Text,
  Toolbar,
  ToolbarButton,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import {
  AddRegular,
  DeleteRegular,
  HistoryRegular,
  LibraryRegular,
  PeopleRegular,
  PeopleTeamRegular,
} from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { NodeDto } from "../api/types";
import { can } from "../components/me";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatRelative, levelLabel } from "../components/format";
import { GroupsDialog } from "../dialogs/GroupsDialog";
import { PermissionsDialog } from "../dialogs/PermissionsDialog";
import { PromptDialog } from "../dialogs/PromptDialog";

const useStyles = makeStyles({
  hero: {
    padding: "24px",
    borderRadius: tokens.borderRadiusLarge,
    background: `linear-gradient(120deg, ${tokens.colorBrandBackground} 0%, ${tokens.colorBrandBackgroundPressed} 100%)`,
    color: tokens.colorNeutralForegroundOnBrand,
    marginBottom: "12px",
    display: "flex",
    flexDirection: "column",
    gap: "4px",
  },
  grid: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: "12px", marginTop: "8px" },
  link: { textDecoration: "none" },
  muted: { color: tokens.colorNeutralForeground3 },
});

export function SiteView({ site }: { site: NodeDto }) {
  const s = useStyles();
  const qc = useQueryClient();
  const libraries = useQuery({
    queryKey: ["libraries", site.id],
    queryFn: () => api<NodeDto[]>(`/api/v1/sites/${site.id}/libraries`),
  });
  const [dialog, setDialog] = useState<"library" | "members" | "permissions" | null>(null);

  return (
    <>
      <div className={s.hero}>
        <Text as="h1" size={800} weight="semibold" style={{ color: "inherit" }}>
          {site.title ?? site.name}
        </Text>
        {site.description && <Text style={{ color: "inherit" }}>{site.description}</Text>}
        <Text size={200} style={{ color: "inherit", opacity: 0.85 }}>
          {site.path} · Your access: {site.myLevel ? levelLabel[site.myLevel] : "None"}
        </Text>
      </div>

      <Toolbar aria-label="Site commands" style={{ paddingLeft: 0, flexWrap: "wrap" }}>
        {can(site, "MANAGE_LISTS") && (
          <ToolbarButton icon={<AddRegular />} appearance="primary" onClick={() => setDialog("library")}>
            New document library
          </ToolbarButton>
        )}
        <ToolbarButton icon={<PeopleTeamRegular />} onClick={() => setDialog("members")}>
          Members
        </ToolbarButton>
        {can(site, "MANAGE_PERMISSIONS") && (
          <ToolbarButton icon={<PeopleRegular />} onClick={() => setDialog("permissions")}>
            Site permissions
          </ToolbarButton>
        )}
        <Link to={`/recycle-bin?siteId=${site.id}`}>
          <ToolbarButton icon={<DeleteRegular />}>Recycle bin</ToolbarButton>
        </Link>
        {can(site, "MANAGE_WEB") && (
          <Link to={`/audit?siteId=${site.id}`}>
            <ToolbarButton icon={<HistoryRegular />}>Audit log</ToolbarButton>
          </Link>
        )}
      </Toolbar>

      <Text as="h2" size={500} weight="semibold" block style={{ marginTop: 12 }}>
        Document libraries
      </Text>
      <ErrorBar error={libraries.error} />
      {libraries.isPending ? (
        <Loading />
      ) : libraries.data?.length ? (
        <div className={s.grid}>
          {libraries.data.map((lib) => (
            <Link key={lib.id} to={routeFor(lib.path)} className={s.link}>
              <Card style={{ height: "100%" }}>
                <CardHeader
                  image={<LibraryRegular style={{ fontSize: 28, color: tokens.colorBrandForeground1 }} />}
                  header={<Text weight="semibold">{lib.title ?? lib.name}</Text>}
                  description={<Text size={200}>{lib.name}</Text>}
                />
                {lib.description && <Text className={s.muted}>{lib.description}</Text>}
                <Text size={200} className={s.muted}>
                  Updated {formatRelative(lib.modifiedAt)}
                </Text>
              </Card>
            </Link>
          ))}
        </div>
      ) : (
        <Empty title="No libraries you can access" />
      )}

      {dialog === "library" && (
        <PromptDialog
          title="New document library"
          label="Library name"
          submitLabel="Create"
          onClose={() => setDialog(null)}
          onSubmit={async (name) => {
            await api(`/api/v1/sites/${site.id}/libraries`, { method: "POST", body: { name } });
            await qc.invalidateQueries({ queryKey: ["libraries", site.id] });
          }}
        />
      )}
      {dialog === "members" && <GroupsDialog site={site} onClose={() => setDialog(null)} />}
      {dialog === "permissions" && <PermissionsDialog node={site} onClose={() => setDialog(null)} />}
    </>
  );
}

import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Card,
  CardHeader,
  Menu,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  Text,
  Toolbar,
  ToolbarButton,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import {
  AddRegular,
  DeleteRegular,
  DocumentOnePageRegular,
  DocumentAddRegular,
  GlobeAddRegular,
  GlobeRegular,
  HistoryRegular,
  NewsRegular,
  LibraryRegular,
  LinkRegular,
  PaintBrushRegular,
  PeopleRegular,
  PeopleTeamRegular,
  TextBulletListSquareRegular,
} from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { ColumnDto, DesignResponse, NodeDto, PageDetail, PageKind, PageSummary } from "../api/types";
import { can, useMe } from "../components/me";
import { ErrorBar, Loading } from "../components/States";
import { formatDate, formatRelative } from "../components/format";
import { ChangeLookDialog } from "../dialogs/ChangeLookDialog";
import { GroupsDialog } from "../dialogs/GroupsDialog";
import { ItemDialog } from "../dialogs/ItemDialog";
import { NewListDialog } from "../dialogs/NewListDialog";
import { NewSubsiteDialog } from "../dialogs/NewSubsiteDialog";
import { PermissionsDialog } from "../dialogs/PermissionsDialog";
import { PromptDialog } from "../dialogs/PromptDialog";
import { PageScreen } from "../pagekit/PageView";
import { NewPageDialog } from "../pagekit/SitePagesView";
import { NewsPart } from "../pagekit/WebParts";

const useStyles = makeStyles({
  layout: {
    display: "grid", gridTemplateColumns: "minmax(0, 2fr) minmax(260px, 1fr)", gap: "16px", marginTop: "12px",
    "@media (max-width: 900px)": { gridTemplateColumns: "minmax(0, 1fr)" },
  },
  column: { display: "flex", flexDirection: "column", gap: "16px", minWidth: 0 },
  section: { display: "flex", flexDirection: "column", gap: "8px" },
  sectionHead: { display: "flex", alignItems: "center", justifyContent: "space-between", gap: "8px" },
  grid: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: "10px" },
  quick: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(150px, 1fr))", gap: "8px" },
  quickLink: {
    display: "flex", alignItems: "center", gap: "8px", padding: "10px 12px", textDecoration: "none",
    borderRadius: tokens.borderRadiusLarge, backgroundColor: tokens.colorBrandBackground2,
    color: tokens.colorBrandForeground2, fontWeight: tokens.fontWeightSemibold,
    ":hover": { backgroundColor: tokens.colorBrandBackground2Hover },
  },
  link: { textDecoration: "none" },
  welcome: { whiteSpace: "pre-wrap" },
  muted: { color: tokens.colorNeutralForeground3 },
  news: { display: "flex", flexDirection: "column", gap: "4px", cursor: "pointer" },
  newsBody: { whiteSpace: "pre-wrap", display: "-webkit-box", WebkitLineClamp: 3, WebkitBoxOrient: "vertical", overflow: "hidden" },
});

type DialogKind = "library" | "list" | "members" | "permissions" | "look" | "subsite" | PageKind | null;

const HOME_NEWS = { id: "home-news", type: "news" as const, props: { title: "News", limit: 4, layout: "cards" } };

/**
 * A site's home page: a published modern page chosen as the home page, or else the built-in home assembled
 * from the site's design (welcome, quick links, news, libraries, lists).
 */
export function SiteView({ site }: { site: NodeDto }) {
  const s = useStyles();
  const qc = useQueryClient();
  const design = useQuery({ queryKey: ["design", site.id], queryFn: () => api<DesignResponse>(`/api/v1/sites/${site.id}/design`) });
  const libraries = useQuery({ queryKey: ["libraries", site.id], queryFn: () => api<NodeDto[]>(`/api/v1/sites/${site.id}/libraries`) });
  const lists = useQuery({ queryKey: ["lists", site.id], queryFn: () => api<NodeDto[]>(`/api/v1/sites/${site.id}/lists`) });
  const [dialog, setDialog] = useState<DialogKind>(null);
  const me = useMe();
  const subsites = useQuery({ queryKey: ["subsites", site.id], queryFn: () => api<NodeDto[]>(`/api/v1/sites/${site.id}/subsites`) });
  const homePageId = design.data?.homePageId;
  const homePage = useQuery({
    queryKey: ["page", "id", homePageId],
    queryFn: () => api<PageDetail>(`/api/v1/pages/${homePageId}`),
    enabled: !!homePageId,
  });
  const news = useQuery({
    queryKey: ["news", site.id, HOME_NEWS.props.limit],
    queryFn: () => api<PageSummary[]>(`/api/v1/sites/${site.id}/news?limit=${HOME_NEWS.props.limit}`),
    enabled: design.isSuccess && !homePageId,
  });

  const home = design.data?.design.home;
  const newsList = lists.data?.find((l) => l.id === home?.newsListId);

  return (
    <>
      <Toolbar aria-label="Site commands" style={{ paddingLeft: 0, flexWrap: "wrap" }}>
        {can(site, "ADD_ITEMS") && (
          <Menu>
            <MenuTrigger disableButtonEnhancement>
              <ToolbarButton appearance="primary" icon={<AddRegular />}>New</ToolbarButton>
            </MenuTrigger>
            <MenuPopover>
              <MenuList>
                <MenuItem icon={<DocumentAddRegular />} onClick={() => setDialog("PAGE")}>Page</MenuItem>
                <MenuItem icon={<NewsRegular />} onClick={() => setDialog("NEWS")}>News post</MenuItem>
                {can(site, "MANAGE_LISTS") && (
                  <>
                    <MenuItem icon={<LibraryRegular />} onClick={() => setDialog("library")}>Document library</MenuItem>
                    <MenuItem icon={<TextBulletListSquareRegular />} onClick={() => setDialog("list")}>List</MenuItem>
                  </>
                )}
                {can(site, "MANAGE_WEB") && (
                  <MenuItem icon={<GlobeAddRegular />} onClick={() => setDialog("subsite")}>Subsite</MenuItem>
                )}
              </MenuList>
            </MenuPopover>
          </Menu>
        )}
        <Link to={routeFor(site.path + "/SitePages")}>
          <ToolbarButton icon={<DocumentOnePageRegular />}>Pages</ToolbarButton>
        </Link>
        {!me.anonymous && <ToolbarButton icon={<PeopleTeamRegular />} onClick={() => setDialog("members")}>Members</ToolbarButton>}
        {can(site, "MANAGE_PERMISSIONS") && (
          <ToolbarButton icon={<PeopleRegular />} onClick={() => setDialog("permissions")}>Site permissions</ToolbarButton>
        )}
        {can(site, "MANAGE_WEB") && (
          <ToolbarButton icon={<PaintBrushRegular />} onClick={() => setDialog("look")}>Change the look</ToolbarButton>
        )}
        {!me.anonymous && (
          <Link to={`/recycle-bin?siteId=${site.id}`}>
            <ToolbarButton icon={<DeleteRegular />}>Recycle bin</ToolbarButton>
          </Link>
        )}
        {can(site, "MANAGE_WEB") && (
          <Link to={`/audit?siteId=${site.id}`}>
            <ToolbarButton icon={<HistoryRegular />}>Audit log</ToolbarButton>
          </Link>
        )}
      </Toolbar>

      <ErrorBar error={design.error ?? homePage.error} />
      {homePageId ? (
        homePage.data ? <PageScreen detail={homePage.data} asHome /> : homePage.isPending ? <Loading /> : null
      ) : !home ? (
        <Loading />
      ) : (
        <div className={s.layout}>
          <div className={s.column}>
            {(home.welcomeTitle || home.welcome) && (
              <Card>
                {home.welcomeTitle && <Text as="h2" size={600} weight="semibold">{home.welcomeTitle}</Text>}
                {home.welcome && <Text className={s.welcome}>{home.welcome}</Text>}
              </Card>
            )}
            {!!news.data?.length && <NewsPart part={HOME_NEWS} siteId={site.id} />}
            {home.showNews && newsList && <NewsSection list={newsList} />}
            {home.showLibraries && (
              <ContainerSection title="Document libraries" nodes={libraries.data} error={libraries.error}
                icon={<LibraryRegular style={{ fontSize: 26, color: tokens.colorBrandForeground1 }} />} />
            )}
            {home.showLists && (
              <ContainerSection title="Lists" nodes={lists.data} error={lists.error}
                icon={<TextBulletListSquareRegular style={{ fontSize: 26, color: tokens.colorBrandForeground1 }} />} />
            )}
          </div>
          <div className={s.column}>
            {home.quickLinks.length > 0 && (
              <section className={s.section} aria-label="Quick links">
                <Text as="h2" size={500} weight="semibold">Quick links</Text>
                <div className={s.quick}>
                  {home.quickLinks.map((l, i) =>
                    l.url?.startsWith("/") ? (
                      <Link key={i} to={routeFor(l.url)} className={s.quickLink}><LinkRegular />{l.label}</Link>
                    ) : (
                      <a key={i} href={l.url} target="_blank" rel="noopener noreferrer" className={s.quickLink}><LinkRegular />{l.label}</a>
                    ),
                  )}
                </div>
              </section>
            )}
            <Card>
              <Text weight="semibold">About this site</Text>
              <Text size={200} className={s.muted}>{site.path}</Text>
              <Text size={200} className={s.muted}>Created {formatDate(site.createdAt)} by {site.createdBy?.displayName}</Text>
            </Card>
          </div>
        </div>
      )}
      {!!subsites.data?.length && (
        <div style={{ marginTop: 16 }}>
          <ContainerSection title="Subsites" nodes={subsites.data} error={subsites.error}
            icon={<GlobeRegular style={{ fontSize: 26, color: tokens.colorBrandForeground1 }} />} />
        </div>
      )}

      {dialog === "library" && (
        <PromptDialog title="New document library" label="Library name" submitLabel="Create" onClose={() => setDialog(null)}
          onSubmit={async (name) => {
            await api(`/api/v1/sites/${site.id}/libraries`, { method: "POST", body: { name } });
            await qc.invalidateQueries({ queryKey: ["libraries", site.id] });
          }} />
      )}
      {dialog === "list" && <NewListDialog site={site} onClose={() => setDialog(null)} />}
      {dialog === "members" && <GroupsDialog site={site} onClose={() => setDialog(null)} />}
      {dialog === "permissions" && <PermissionsDialog node={site} onClose={() => setDialog(null)} />}
      {dialog === "look" && <ChangeLookDialog site={site} onClose={() => setDialog(null)} />}
      {dialog === "subsite" && <NewSubsiteDialog site={site} onClose={() => setDialog(null)} />}
      {(dialog === "PAGE" || dialog === "NEWS") && <NewPageDialog site={site} kind={dialog} onClose={() => setDialog(null)} />}
    </>
  );

}

function ContainerSection(p: { title: string; nodes?: NodeDto[]; error: unknown; icon: React.ReactElement }) {
  const s = useStyles();
  return (
    <section className={s.section} aria-label={p.title}>
      <Text as="h2" size={500} weight="semibold">{p.title}</Text>
      <ErrorBar error={p.error} />
      {!p.nodes ? <Loading /> : !p.nodes.length ? <Text className={s.muted}>None yet.</Text> : (
        <div className={s.grid}>
          {p.nodes.map((n) => (
            <Link key={n.id} to={routeFor(n.path)} className={s.link}>
              <Card style={{ height: "100%" }}>
                <CardHeader image={p.icon} header={<Text weight="semibold">{n.title ?? n.name}</Text>}
                  description={<Text size={200} className={s.muted}>Updated {formatRelative(n.modifiedAt)}</Text>} />
                {n.description && <Text size={200} className={s.muted}>{n.description}</Text>}
              </Card>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}

function NewsSection({ list }: { list: NodeDto }) {
  const s = useStyles();
  const items = useQuery({ queryKey: ["children", list.id], queryFn: () => api<NodeDto[]>(`/api/v1/nodes/${list.id}/children`) });
  const [adding, setAdding] = useState(false);
  const [open, setOpen] = useState<NodeDto | null>(null);
  const columns = useQuery({ queryKey: ["columns", list.id], queryFn: () => api<ColumnDto[]>(`/api/v1/nodes/${list.id}/columns`) });
  const today = new Date().toISOString().slice(0, 10);
  const latest = (items.data ?? [])
    .filter((n) => !n.metadata?.Expires || String(n.metadata.Expires) >= today)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .slice(0, 5);
  return (
    <section className={s.section} aria-label="News">
      <div className={s.sectionHead}>
        <Text as="h2" size={500} weight="semibold">{list.title ?? "News"}</Text>
        <div style={{ display: "flex", gap: 4 }}>
          {can(list, "ADD_ITEMS") && <Button size="small" icon={<AddRegular />} onClick={() => setAdding(true)}>Add news</Button>}
          <Link to={routeFor(list.path)}><Button size="small" appearance="subtle">See all</Button></Link>
        </div>
      </div>
      {items.isPending ? <Loading /> : !latest.length ? <Text className={s.muted}>No news yet.</Text> : latest.map((n) => (
        <Card key={n.id} className={s.news} onClick={() => setOpen(n)}>
          <Text weight="semibold" size={400}>{n.title}</Text>
          {typeof n.metadata?.Body === "string" && <Text className={s.newsBody}>{n.metadata.Body}</Text>}
          <Text size={200} className={s.muted}>{n.createdBy?.displayName} · {formatDate(n.createdAt)}</Text>
        </Card>
      ))}
      {adding && columns.data && <ItemDialog list={list} columns={columns.data} onClose={() => setAdding(false)} />}
      {open && columns.data && <ItemDialog list={list} columns={columns.data} item={open} onClose={() => setOpen(null)} />}
    </section>
  );
}

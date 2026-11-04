import { useLocation } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { NodeDetail } from "../api/types";
import { Loading, NotFound } from "../components/States";
import { OldLink } from "../components/OldLink";
import { SiteChrome } from "../components/SiteChrome";
import { SiteView } from "./SiteView";
import { LibraryView } from "./LibraryView";
import { FileView } from "./FileView";
import { ListView } from "./ListView";
import type { PageDetail } from "../api/types";
import { PageScreen } from "../pagekit/PageView";
import { SitePagesView } from "../pagekit/SitePagesView";

/** /sites/<site>/SitePages[/<page>.aspx]: modern pages live beside the content tree. */
const PAGES = /^(\/sites\/[^/]+)\/sitepages(\/.*)?$/i;

/**
 * Browser URLs mirror SharePoint server-relative URLs (/sites/hr/Shared Documents/Policies),
 * so links copied from a migrated farm keep working after the URL redirect service rewrites the host.
 * Everything inside a site is shown with the site's header, navigation and theme.
 */
export function ResolvePage() {
  const { pathname } = useLocation();
  const path = safeDecode(pathname);
  const pages = PAGES.exec(path);
  if (pages) {
    const rest = (pages[2] ?? "").replace(/^\/+|\/+$/g, "");
    return rest ? <PageRoute path={path} /> : <PagesRoute sitePath={pages[1]} />;
  }
  return <NodeRoute path={path} />;
}

function PageRoute({ path }: { path: string }) {
  const page = useQuery({
    queryKey: ["page", path.toLowerCase()],
    queryFn: () => api<PageDetail>(`/api/v1/pages/resolve?path=${encodeURIComponent(path)}`),
    retry: false,
  });
  if (page.isPending) return <Loading />;
  if (page.error || !page.data) return <OldLink error={page.error} />;
  return (
    <SiteChrome siteId={page.data.page.siteId}>
      <PageScreen detail={page.data} />
    </SiteChrome>
  );
}

function PagesRoute({ sitePath }: { sitePath: string }) {
  const site = useQuery({
    queryKey: ["resolve", sitePath.toLowerCase()],
    queryFn: () => api<NodeDetail>(`/api/v1/resolve?path=${encodeURIComponent(sitePath)}`),
  });
  if (site.isPending) return <Loading />;
  if (site.error || !site.data || site.data.node.kind !== "SITE") return <NotFound error={site.error} />;
  return (
    <SiteChrome siteId={site.data.node.id}>
      <SitePagesView site={site.data.node} />
    </SiteChrome>
  );
}

function NodeRoute({ path }: { path: string }) {
  const detail = useQuery({
    queryKey: ["resolve", path.toLowerCase()],
    queryFn: () => api<NodeDetail>(`/api/v1/resolve?path=${encodeURIComponent(path)}`),
  });

  if (detail.isPending) return <Loading />;
  if (detail.error || !detail.data) return <OldLink error={detail.error} />;

  const { node, breadcrumbs } = detail.data;
  return (
    <SiteChrome siteId={breadcrumbs[0].id}>
      <Content node={node} breadcrumbs={breadcrumbs} />
    </SiteChrome>
  );
}

function Content({ node, breadcrumbs }: NodeDetail) {
  switch (node.kind) {
    case "SITE":
      return <SiteView site={node} />;
    case "LIBRARY":
    case "FOLDER":
      return <LibraryView container={node} breadcrumbs={breadcrumbs} />;
    case "FILE":
      return <FileView file={node} breadcrumbs={breadcrumbs} />;
    case "LIST":
      return <ListView list={node} breadcrumbs={breadcrumbs} />;
    case "LIST_ITEM":
      return <ItemInList itemId={node.id} listId={breadcrumbs[breadcrumbs.length - 2].id} breadcrumbs={breadcrumbs} />;
  }
}

/** A direct link to a list item opens the list with that item's form. */
function ItemInList({ itemId, listId, breadcrumbs }: { itemId: string; listId: string; breadcrumbs: NodeDetail["breadcrumbs"] }) {
  const list = useQuery({ queryKey: ["node", listId], queryFn: () => api<NodeDetail>(`/api/v1/nodes/${listId}`) });
  if (list.isPending) return <Loading />;
  if (!list.data) return <NotFound error={list.error} />;
  return <ListView list={list.data.node} breadcrumbs={breadcrumbs} openItemId={itemId} />;
}

function safeDecode(p: string) {
  try {
    return decodeURIComponent(p);
  } catch {
    return p;
  }
}

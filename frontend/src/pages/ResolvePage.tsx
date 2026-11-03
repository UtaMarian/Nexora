import { useLocation } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { NodeDetail } from "../api/types";
import { Loading, NotFound } from "../components/States";
import { SiteView } from "./SiteView";
import { LibraryView } from "./LibraryView";
import { FileView } from "./FileView";

/**
 * Browser URLs mirror SharePoint server-relative URLs (/sites/hr/Shared Documents/Policies),
 * so links copied from a migrated farm keep working after the URL redirect service rewrites the host.
 */
export function ResolvePage() {
  const { pathname } = useLocation();
  const path = safeDecode(pathname);
  const detail = useQuery({
    queryKey: ["resolve", path.toLowerCase()],
    queryFn: () => api<NodeDetail>(`/api/v1/resolve?path=${encodeURIComponent(path)}`),
  });

  if (detail.isPending) return <Loading />;
  if (detail.error || !detail.data) return <NotFound error={detail.error} />;

  const { node, breadcrumbs } = detail.data;
  switch (node.kind) {
    case "SITE":
      return <SiteView site={node} />;
    case "LIBRARY":
    case "FOLDER":
      return <LibraryView container={node} breadcrumbs={breadcrumbs} />;
    case "FILE":
      return <FileView file={node} breadcrumbs={breadcrumbs} />;
  }
}

function safeDecode(p: string) {
  try {
    return decodeURIComponent(p);
  } catch {
    return p;
  }
}

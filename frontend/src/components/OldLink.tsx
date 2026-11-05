import { Navigate, useLocation } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { ApiError, api, routeFor } from "../api/client";
import { Loading, NotFound } from "./States";

/**
 * Shown when a URL matches nothing. It may be an old SharePoint link (/_layouts/15/Doc.aspx?sourcedoc=...,
 * /sites/hr/Lists/Tasks/DispForm.aspx?ID=3, /:w:/r/...), so ask where that content lives now before giving up.
 */
export function OldLink({ error }: { error?: unknown }) {
  const { pathname, search } = useLocation();
  const missing = !error || (error instanceof ApiError && error.status === 404);
  const url = pathname + search;
  const target = useQuery({
    queryKey: ["redirect", url],
    queryFn: () => api<{ path: string }>(`/api/v1/redirects/resolve?url=${encodeURIComponent(url)}`),
    enabled: missing,
    retry: false,
  });
  if (!missing) return <NotFound error={error} />;
  if (target.isPending) return <Loading />;
  const path = target.data?.path;
  // a link that resolves to the page we are on would loop
  if (!path || routeFor(path).toLowerCase() === pathname.toLowerCase()) return <NotFound error={error} />;
  return <Navigate to={routeFor(path)} replace />;
}

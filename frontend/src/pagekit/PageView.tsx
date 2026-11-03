import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
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
  MessageBar,
  MessageBarActions,
  MessageBarBody,
  Persona,
  Text,
  Toolbar,
  ToolbarButton,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import { DeleteRegular, EditRegular, HistoryRegular, HomeRegular, SendRegular } from "@fluentui/react-icons";
import { api, contentUrl, routeFor } from "../api/client";
import type { PageDetail, PageVersion } from "../api/types";
import { ErrorBar, Loading } from "../components/States";
import { formatDate } from "../components/format";
import { CanvasView } from "./CanvasView";
import { PageEditor } from "./PageEditor";

const useStyles = makeStyles({
  banner: {
    width: "100%", aspectRatio: "4 / 1", minHeight: "120px", objectFit: "cover", display: "block",
    borderRadius: tokens.borderRadiusLarge,
  },
  head: { display: "flex", flexDirection: "column", gap: "8px", margin: "8px 0 20px" },
  meta: { display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", color: tokens.colorNeutralForeground3 },
  description: { color: tokens.colorNeutralForeground2, fontSize: tokens.fontSizeBase400 },
  commands: { paddingLeft: 0, flexWrap: "wrap" },
});

export function PageBanner({ imageId }: { imageId?: string }) {
  const s = useStyles();
  if (!imageId) return null;
  return <img src={contentUrl(imageId)} alt="" className={s.banner} />;
}

/**
 * A modern page: what readers see, plus the editing commands for people who may edit it.
 * {@code ?mode=edit} opens the editor (SharePoint uses ?Mode=Edit the same way).
 */
export function PageScreen({ detail, asHome }: { detail: PageDetail; asHome?: boolean }) {
  const s = useStyles();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const editing = detail.canEdit && params.get("mode")?.toLowerCase() === "edit";
  const [versions, setVersions] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const { page } = detail;

  const refresh = () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: ["page"] }),
      qc.invalidateQueries({ queryKey: ["pages", page.siteId] }),
      qc.invalidateQueries({ queryKey: ["news", page.siteId] }),
      qc.invalidateQueries({ queryKey: ["design", page.siteId] }),
    ]);

  const act = async (fn: () => Promise<unknown>) => {
    setError(null);
    try {
      await fn();
      await refresh();
    } catch (err) {
      setError(err);
    }
  };

  if (editing) {
    return (
      <PageEditor
        key={page.id}
        detail={detail}
        onClose={() => {
          if (asHome) navigate(routeFor(page.url));   // the editor URL is the page's own
          params.delete("mode");
          setParams(params, { replace: true });
        }}
      />
    );
  }

  const edit = () => {
    if (asHome) navigate(routeFor(page.url) + "?mode=edit");
    else setParams({ mode: "edit" });
  };
  const shown = detail.published ?? detail.draft;

  return (
    <article>
      {detail.canEdit && (
        <Toolbar aria-label="Page commands" className={s.commands}>
          <ToolbarButton appearance="primary" icon={<EditRegular />} onClick={edit}>Edit</ToolbarButton>
          {page.hasDraft && (
            <ToolbarButton icon={<SendRegular />}
              onClick={() => act(() => api(`/api/v1/pages/${page.id}/publish`, { method: "POST" }))}>
              Publish
            </ToolbarButton>
          )}
          {page.published && <ToolbarButton icon={<HistoryRegular />} onClick={() => setVersions(true)}>Version history</ToolbarButton>}
          {detail.canSetHome && page.published && !page.home && page.kind === "PAGE" && (
            <ToolbarButton icon={<HomeRegular />}
              onClick={() => act(() => api(`/api/v1/sites/${page.siteId}/home-page`, { method: "PUT", body: { pageId: page.id } }))}>
              Make home page
            </ToolbarButton>
          )}
          {detail.canSetHome && page.home && (
            <ToolbarButton icon={<HomeRegular />}
              onClick={() => act(() => api(`/api/v1/sites/${page.siteId}/home-page`, { method: "PUT", body: { pageId: null } }))}>
              Use the standard home page
            </ToolbarButton>
          )}
          {detail.canDelete && (
            <ToolbarButton icon={<DeleteRegular />}
              onClick={() => {
                if (!window.confirm(`Delete "${page.title}"? This cannot be undone.`)) return;
                void act(async () => {
                  await api(`/api/v1/pages/${page.id}`, { method: "DELETE" });
                  const site = page.url.slice(0, page.url.indexOf("/SitePages/"));
                  navigate(routeFor(site + "/SitePages"));
                });
              }}>
              Delete
            </ToolbarButton>
          )}
        </Toolbar>
      )}
      <ErrorBar error={error} />
      {detail.canEdit && !page.published && (
        <MessageBar intent="warning" style={{ marginBottom: 12 }}>
          <MessageBarBody>This {page.kind === "NEWS" ? "news post" : "page"} is a draft. Only people who can edit pages see it.</MessageBarBody>
        </MessageBar>
      )}
      {detail.canEdit && page.published && page.hasDraft && (
        <MessageBar intent="info" style={{ marginBottom: 12 }}>
          <MessageBarBody>There are unpublished changes. Readers still see the published version.</MessageBarBody>
          <MessageBarActions>
            <Button size="small" onClick={edit}>Continue editing</Button>
          </MessageBarActions>
        </MessageBar>
      )}

      {shown && (
        <>
          <PageBanner imageId={shown.bannerImageId} />
          {!(asHome && page.kind === "PAGE") && (
            <header className={s.head}>
              <Text as="h1" size={800} weight="semibold">{shown.title}</Text>
              {shown.description && <Text className={s.description}>{shown.description}</Text>}
              {page.kind === "NEWS" && (
                <div className={s.meta}>
                  <Persona size="small" name={page.author?.displayName} avatar={{ color: "colorful" }} />
                  <Text size={200}>{page.publishedAt ? formatDate(page.publishedAt) : "Not published yet"}</Text>
                  <Badge appearance="tint">News</Badge>
                </div>
              )}
            </header>
          )}
          <CanvasView canvas={shown.canvas} siteId={page.siteId} />
        </>
      )}
      {versions && <VersionsDialog detail={detail} onClose={() => setVersions(false)} onRestored={edit} />}
    </article>
  );
}

function VersionsDialog({ detail, onClose, onRestored }: { detail: PageDetail; onClose: () => void; onRestored: () => void }) {
  const qc = useQueryClient();
  const versions = useQuery({
    queryKey: ["page-versions", detail.page.id],
    queryFn: () => api<PageVersion[]>(`/api/v1/pages/${detail.page.id}/versions`),
  });
  const [error, setError] = useState<unknown>(null);
  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 560 }}>
        <DialogBody>
          <DialogTitle>Version history</DialogTitle>
          <DialogContent>
            <ErrorBar error={error ?? versions.error} />
            {versions.isPending ? <Loading /> : (
              <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
                {versions.data?.map((v) => (
                  <div key={v.version} style={{ display: "flex", alignItems: "center", gap: 10, padding: "6px 0",
                    borderBottom: `1px solid ${tokens.colorNeutralStroke3}` }}>
                    <Badge appearance={v.version === detail.page.version ? "filled" : "outline"}>{v.version}.0</Badge>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <Text block weight="semibold">{v.title}</Text>
                      <Text size={200} style={{ color: tokens.colorNeutralForeground3 }}>
                        {v.publishedBy?.displayName} · {formatDate(v.publishedAt)}
                      </Text>
                    </div>
                    {v.version !== detail.page.version && (
                      <Button size="small" onClick={async () => {
                        setError(null);
                        try {
                          await api(`/api/v1/pages/${detail.page.id}/versions/${v.version}/restore`, { method: "POST" });
                          await qc.invalidateQueries({ queryKey: ["page"] });
                          onClose();
                          onRestored();
                        } catch (err) {
                          setError(err);
                        }
                      }}>
                        Restore
                      </Button>
                    )}
                  </div>
                ))}
              </div>
            )}
            <Text size={200} style={{ color: tokens.colorNeutralForeground3, display: "block", marginTop: 10 }}>
              Restoring copies the version into a draft; publish it to make it current again.
            </Text>
          </DialogContent>
          <DialogActions>
            <Button onClick={onClose}>Close</Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Card,
  CardHeader,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  Field,
  Input,
  Text,
  Textarea,
  makeStyles,
  mergeClasses,
  tokens,
} from "@fluentui/react-components";
import { AddRegular, GlobeRegular } from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { NodeDto, Templates } from "../api/types";
import { Empty, ErrorBar, Loading } from "../components/States";
import { useMe } from "../components/me";
import { formatRelative } from "../components/format";

const useStyles = makeStyles({
  head: { display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "16px", gap: "12px" },
  grid: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: "12px" },
  card: { textDecoration: "none", height: "100%" },
  icon: {
    width: "40px",
    height: "40px",
    borderRadius: tokens.borderRadiusMedium,
    backgroundColor: tokens.colorBrandBackground2,
    color: tokens.colorBrandForeground2,
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    fontSize: "20px",
  },
  desc: { color: tokens.colorNeutralForeground3 },
  form: { display: "flex", flexDirection: "column", gap: "12px" },
  templates: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(190px, 1fr))", gap: "8px" },
  template: {
    border: `1px solid ${tokens.colorNeutralStroke2}`, borderRadius: tokens.borderRadiusLarge, padding: "10px",
    background: tokens.colorNeutralBackground1, color: tokens.colorNeutralForeground1, cursor: "pointer",
    textAlign: "left", font: "inherit", display: "flex", flexDirection: "column", gap: "4px",
  },
  templateOn: { border: `2px solid ${tokens.colorBrandStroke1}`, padding: "9px" },
});

export function HomePage() {
  const s = useStyles();
  const sites = useQuery({ queryKey: ["sites"], queryFn: () => api<NodeDto[]>("/api/v1/sites") });
  const [creating, setCreating] = useState(false);
  const me = useMe();
  const nothingPublic = me.anonymous && sites.isSuccess && !sites.data.length;
  useEffect(() => {
    if (nothingPublic) window.dispatchEvent(new Event("nexora:unauthorized"));
  }, [nothingPublic]);

  return (
    <>
      <div className={s.head}>
        <Text as="h1" size={700} weight="semibold">
          {me.anonymous ? "Public sites" : "Your sites"}
        </Text>
        {!me.anonymous && (
          <Button appearance="primary" icon={<AddRegular />} onClick={() => setCreating(true)}>
            Create site
          </Button>
        )}
      </div>
      <ErrorBar error={sites.error} />
      {sites.isPending ? (
        <Loading />
      ) : sites.data?.length ? (
        <div className={s.grid}>
          {sites.data.map((site) => (
            <Link key={site.id} to={routeFor(site.path)} className={s.card}>
              <Card style={{ height: "100%" }}>
                <CardHeader
                  image={
                    <div className={s.icon}>
                      <GlobeRegular />
                    </div>
                  }
                  header={<Text weight="semibold">{site.title ?? site.name}</Text>}
                  description={<Text size={200}>{site.path}</Text>}
                />
                {site.description && <Text className={s.desc}>{site.description}</Text>}
                <Text size={200} className={s.desc}>
                  Updated {formatRelative(site.modifiedAt)}
                </Text>
              </Card>
            </Link>
          ))}
        </div>
      ) : (
        <Empty title="No sites have been shared with you yet">
          <Text>Ask a site's owner for access, or create a site to start sharing documents with your team.</Text>
        </Empty>
      )}
      {creating && <NewSiteDialog onClose={() => setCreating(false)} />}
    </>
  );
}

function NewSiteDialog({ onClose }: { onClose: () => void }) {
  const s = useStyles();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [title, setTitle] = useState("");
  const [url, setUrl] = useState("");
  const [urlTouched, setUrlTouched] = useState(false);
  const [description, setDescription] = useState("");
  const [template, setTemplate] = useState("TEAM");
  const templates = useQuery({ queryKey: ["templates"], queryFn: () => api<Templates>("/api/v1/templates"), staleTime: Infinity });

  const slug = (t: string) =>
    t
      .toLowerCase()
      .normalize("NFKD")
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 63);

  const create = useMutation({
    mutationFn: () => api<NodeDto>("/api/v1/sites", { method: "POST", body: { url, title, description, template } }),
    onSuccess: (site) => {
      qc.invalidateQueries({ queryKey: ["sites"] });
      navigate(routeFor(site.path));
    },
  });

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 680 }}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            create.mutate();
          }}
        >
          <DialogBody>
            <DialogTitle>Create a site</DialogTitle>
            <DialogContent className={s.form}>
              <ErrorBar error={create.error} />
              <div className={s.templates} role="radiogroup" aria-label="Site template">
                {templates.data?.sites.map((t) => (
                  <button key={t.id} type="button" role="radio" aria-checked={template === t.id}
                    className={mergeClasses(s.template, template === t.id && s.templateOn)}
                    onClick={() => setTemplate(t.id)}>
                    <Text weight="semibold">{t.title}</Text>
                    <Text size={200} className={s.desc}>{t.description}</Text>
                  </button>
                ))}
              </div>
              <Field label="Site name" required>
                <Input
                  value={title}
                  autoFocus
                  onChange={(_, d) => {
                    setTitle(d.value);
                    if (!urlTouched) setUrl(slug(d.value));
                  }}
                />
              </Field>
              <Field label="Site address" hint={`/sites/${url || "…"}`} required>
                <Input
                  value={url}
                  onChange={(_, d) => {
                    setUrlTouched(true);
                    setUrl(d.value.toLowerCase());
                  }}
                />
              </Field>
              <Field label="Description">
                <Textarea value={description} onChange={(_, d) => setDescription(d.value)} />
              </Field>
              <Text size={200}>
                Every site gets Owners, Members and Visitors groups and a Documents library; the template adds lists,
                libraries, navigation and a theme you can change later. You become an owner.
              </Text>
            </DialogContent>
            <DialogActions>
              <Button onClick={onClose}>Cancel</Button>
              <Button appearance="primary" type="submit" disabled={!title || !url || create.isPending}>
                {create.isPending ? "Creating…" : "Create"}
              </Button>
            </DialogActions>
          </DialogBody>
        </form>
      </DialogSurface>
    </Dialog>
  );
}

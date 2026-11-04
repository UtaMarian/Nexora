import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
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
  Radio,
  RadioGroup,
  Switch,
  Tab,
  TabList,
  Text,
  Textarea,
  makeStyles,
  mergeClasses,
  tokens,
} from "@fluentui/react-components";
import { CheckmarkRegular } from "@fluentui/react-icons";
import { api, ApiError } from "../api/client";
import type { DesignResponse, Hub, Link, NodeDto, SiteDesign } from "../api/types";
import { ErrorBar, Loading } from "../components/States";
import { THEME_PRESETS } from "../components/siteTheme";
import { LinkEditor } from "./LinkEditor";

const useStyles = makeStyles({
  body: { display: "flex", flexDirection: "column", gap: "14px", minHeight: "360px" },
  swatches: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(120px, 1fr))", gap: "10px" },
  swatch: {
    border: `2px solid ${tokens.colorNeutralStroke2}`, borderRadius: tokens.borderRadiusLarge, padding: "8px",
    cursor: "pointer", background: tokens.colorNeutralBackground1, textAlign: "left", display: "flex",
    flexDirection: "column", gap: "6px", color: tokens.colorNeutralForeground1, font: "inherit",
  },
  swatchOn: { border: `2px solid ${tokens.colorNeutralForeground1}` },
  chip: { height: "36px", borderRadius: tokens.borderRadiusMedium, display: "flex", alignItems: "flex-end",
    justifyContent: "flex-end", padding: "4px", color: "white" },
  logoRow: { display: "flex", alignItems: "center", gap: "12px", flexWrap: "wrap" },
  logoPreview: { width: "64px", height: "64px", objectFit: "contain", borderRadius: tokens.borderRadiusMedium,
    border: `1px solid ${tokens.colorNeutralStroke2}` },
});

type TabId = "theme" | "header" | "navigation" | "home" | "hub";

/** SharePoint's "Change the look": theme, header, navigation and home page of a site. */
export function ChangeLookDialog({ site, onClose }: { site: NodeDto; onClose: () => void }) {
  const s = useStyles();
  const qc = useQueryClient();
  const current = useQuery({ queryKey: ["design", site.id], queryFn: () => api<DesignResponse>(`/api/v1/sites/${site.id}/design`) });
  const libraries = useQuery({ queryKey: ["libraries", site.id], queryFn: () => api<NodeDto[]>(`/api/v1/sites/${site.id}/libraries`) });
  const lists = useQuery({ queryKey: ["lists", site.id], queryFn: () => api<NodeDto[]>(`/api/v1/sites/${site.id}/lists`) });
  const hubs = useQuery({ queryKey: ["hubs"], queryFn: () => api<Hub[]>("/api/v1/hubs") });

  const [tab, setTab] = useState<TabId>("theme");
  const [draft, setDraft] = useState<SiteDesign | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [logoUrl, setLogoUrl] = useState<string | undefined>();
  const [hubDraft, setHubDraft] = useState<{ title: string; navigation: Link[] } | null>(null);
  const [joined, setJoined] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (current.data && !draft) {
      setDraft(structuredClone(current.data.design));
      setLogoUrl(current.data.logoUrl);
      setJoined(current.data.joinedHubId ?? "");
      if (current.data.isHub && current.data.hub) {
        setHubDraft({ title: current.data.hub.title, navigation: structuredClone(current.data.hub.navigation) });
      }
    }
  }, [current.data, draft]);

  const siteContent = [...(libraries.data ?? []), ...(lists.data ?? [])];
  const update = (fn: (d: SiteDesign) => void) =>
    setDraft((prev) => {
      if (!prev) return prev;
      const next = structuredClone(prev);
      fn(next);
      return next;
    });

  const save = async () => {
    if (!draft) return;
    setBusy(true);
    setError(null);
    try {
      await api(`/api/v1/sites/${site.id}/design`, { method: "PUT", body: draft });
      if (hubDraft) {
        await api(`/api/v1/sites/${site.id}/hub`, { method: "PUT", body: hubDraft });
      } else if (!current.data?.parent && joined !== (current.data?.joinedHubId ?? "")) {
        await api(`/api/v1/sites/${site.id}/hub-association`, { method: "PUT", body: { hubId: joined || null } });
      }
      await qc.invalidateQueries({ queryKey: ["design"] });   // a hub's changes reach every site in it
      await qc.invalidateQueries({ queryKey: ["hubs"] });
      onClose();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const uploadLogo = async (file: File) => {
    setError(null);
    const form = new FormData();
    form.append("file", file);
    const token = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="))?.split("=")[1];
    const res = await fetch(`/api/v1/sites/${site.id}/logo`, {
      method: "PUT",
      body: form,
      credentials: "same-origin",
      headers: token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {},
    });
    if (!res.ok) {
      const b = await res.json().catch(() => ({}));
      setError(new ApiError(res.status, b.code ?? "error", b.detail ?? "Upload failed"));
      return;
    }
    const d: DesignResponse = await res.json();
    setLogoUrl(d.logoUrl);
    qc.invalidateQueries({ queryKey: ["design", site.id] });
  };

  const removeLogo = async () => {
    const d = await api<DesignResponse>(`/api/v1/sites/${site.id}/logo`, { method: "DELETE" });
    setLogoUrl(d.logoUrl);
    qc.invalidateQueries({ queryKey: ["design", site.id] });
  };

  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 760 }}>
        <DialogBody>
          <DialogTitle>Change the look</DialogTitle>
          <DialogContent className={s.body}>
            <TabList selectedValue={tab} onTabSelect={(_, d) => setTab(d.value as TabId)}>
              <Tab value="theme">Theme</Tab>
              <Tab value="header">Header</Tab>
              <Tab value="navigation">Navigation</Tab>
              <Tab value="home">Home page</Tab>
              <Tab value="hub">Hub</Tab>
            </TabList>
            <ErrorBar error={error ?? current.error} />
            {!draft ? (
              <Loading />
            ) : tab === "theme" ? (
              <>
              {current.data?.hub && !current.data.isHub && (
                <Text>This site is in the {current.data.hub.title} hub and shows the hub's theme. The theme below applies if it leaves the hub.</Text>
              )}
              <div className={s.swatches} role="radiogroup" aria-label="Theme">
                {THEME_PRESETS.map((p) => (
                  <button
                    key={p.id}
                    type="button"
                    role="radio"
                    aria-checked={draft.theme === p.id}
                    className={mergeClasses(s.swatch, draft.theme === p.id && s.swatchOn)}
                    onClick={() => update((d) => void (d.theme = p.id))}
                  >
                    <div className={s.chip} style={{ background: `linear-gradient(120deg, ${p.color}, ${p.color}cc)` }}>
                      {draft.theme === p.id && <CheckmarkRegular />}
                    </div>
                    <Text>{p.label}</Text>
                  </button>
                ))}
              </div>
              </>
            ) : tab === "header" ? (
              <>
                <Field label="Layout">
                  <RadioGroup layout="horizontal" value={draft.header.layout}
                    onChange={(_, d) => update((x) => void (x.header.layout = d.value as SiteDesign["header"]["layout"]))}>
                    <Radio value="compact" label="Compact" />
                    <Radio value="standard" label="Standard" />
                    <Radio value="extended" label="Extended" />
                  </RadioGroup>
                </Field>
                <Field label="Background">
                  <RadioGroup layout="horizontal" value={draft.header.background}
                    onChange={(_, d) => update((x) => void (x.header.background = d.value as SiteDesign["header"]["background"]))}>
                    <Radio value="none" label="None" />
                    <Radio value="soft" label="Soft" />
                    <Radio value="strong" label="Strong" />
                  </RadioGroup>
                </Field>
                <Switch checked={draft.header.showTitle} label="Show the site title"
                  onChange={(_, d) => update((x) => void (x.header.showTitle = d.checked))} />
                <Switch checked={draft.header.showLogo} label="Show the logo"
                  onChange={(_, d) => update((x) => void (x.header.showLogo = d.checked))} />
                <Field label="Site logo" hint="PNG, JPEG, GIF or WebP, up to 512 KB. Square images work best.">
                  <div className={s.logoRow}>
                    {logoUrl ? <img src={logoUrl} alt="Current logo" className={s.logoPreview} /> : <Text size={200}>No logo yet: the site's initials are shown.</Text>}
                    <Button onClick={() => fileInput.current?.click()}>{logoUrl ? "Replace" : "Upload"}</Button>
                    {logoUrl && <Button appearance="subtle" onClick={removeLogo}>Remove</Button>}
                    <input ref={fileInput} type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden
                      onChange={(e) => {
                        const f = e.target.files?.[0];
                        e.target.value = "";
                        if (f) void uploadLogo(f);
                      }} />
                  </div>
                </Field>
              </>
            ) : tab === "navigation" ? (
              <>
                <Text>Links in the site header, in this order. Use site addresses like /sites/hr/Policies or full web addresses. The table button turns a link into a mega menu with columns.</Text>
                <LinkEditor links={draft.navigation} max={25} siteContent={siteContent} menus
                  onChange={(links) => update((x) => void (x.navigation = links))} />
              </>
            ) : tab === "hub" ? (
              hubDraft ? (
                <>
                  <Text>This site is a hub. Everyone in the sites that join it sees this bar and this site's theme.</Text>
                  <Field label="Hub name">
                    <Input value={hubDraft.title} maxLength={120} onChange={(_, d) => setHubDraft({ ...hubDraft, title: d.value })} />
                  </Field>
                  <Field label="Hub navigation">
                    <LinkEditor links={hubDraft.navigation} max={25} siteContent={siteContent} menus
                      onChange={(navigation) => setHubDraft({ ...hubDraft, navigation })} />
                  </Field>
                </>
              ) : current.data?.parent ? (
                <Text>
                  {current.data.hub ? `This subsite is in the ${current.data.hub.title} hub because its parent site is.` : "A subsite follows the hub of its parent site."}
                </Text>
              ) : (
                <>
                  <Text>Joining a hub adds its navigation bar and theme to this site, and this site's news to the hub.</Text>
                  <Field label="Hub" hint="Only a farm administrator can make a site a hub.">
                    <Dropdown
                      value={hubs.data?.find((h) => h.id === joined)?.title ?? "None"}
                      selectedOptions={[joined]}
                      onOptionSelect={(_, d) => setJoined(d.optionValue ?? "")}
                    >
                      <Option value="" text="None">None</Option>
                      {(hubs.data ?? []).map((h) => <Option key={h.id} value={h.id} text={h.title}>{h.title}</Option>)}
                    </Dropdown>
                  </Field>
                </>
              )
            ) : (
              <>
                <Field label="Welcome title">
                  <Input value={draft.home.welcomeTitle} maxLength={120}
                    onChange={(_, d) => update((x) => void (x.home.welcomeTitle = d.value))} />
                </Field>
                <Field label="Welcome message">
                  <Textarea value={draft.home.welcome} rows={4} maxLength={5000} resize="vertical"
                    onChange={(_, d) => update((x) => void (x.home.welcome = d.value))} />
                </Field>
                <Switch checked={draft.home.showNews} label="Show news on the home page"
                  onChange={(_, d) => update((x) => void (x.home.showNews = d.checked))} />
                {draft.home.showNews && (
                  <Field label="News comes from">
                    <Dropdown
                      placeholder="Choose a list"
                      value={lists.data?.find((l) => l.id === draft.home.newsListId)?.title ?? ""}
                      selectedOptions={draft.home.newsListId ? [draft.home.newsListId] : []}
                      onOptionSelect={(_, d) => update((x) => void (x.home.newsListId = d.optionValue ?? null))}
                    >
                      {(lists.data ?? []).map((l) => (
                        <Option key={l.id} value={l.id}>{l.title ?? l.name}</Option>
                      ))}
                    </Dropdown>
                  </Field>
                )}
                <Switch checked={draft.home.showLibraries} label="Show document libraries"
                  onChange={(_, d) => update((x) => void (x.home.showLibraries = d.checked))} />
                <Switch checked={draft.home.showLists} label="Show lists"
                  onChange={(_, d) => update((x) => void (x.home.showLists = d.checked))} />
                <Field label="Quick links">
                  <LinkEditor links={draft.home.quickLinks} max={24} siteContent={siteContent}
                    onChange={(links) => update((x) => void (x.home.quickLinks = links))} />
                </Field>
              </>
            )}
          </DialogContent>
          <DialogActions>
            <Button onClick={onClose}>Cancel</Button>
            <Button appearance="primary" onClick={save} disabled={!draft || busy}>
              {busy ? "Saving…" : "Save"}
            </Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

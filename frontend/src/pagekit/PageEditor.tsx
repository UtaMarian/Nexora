import { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  Button,
  Card,
  Dropdown,
  Field,
  Input,
  Menu,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  MessageBar,
  MessageBarBody,
  Text,
  Textarea,
  Toolbar,
  ToolbarButton,
  Tooltip,
  Option,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import {
  AddRegular,
  ArrowDownRegular,
  ArrowUpRegular,
  DeleteRegular,
  DismissRegular,
  SaveRegular,
  SendRegular,
} from "@fluentui/react-icons";
import { api } from "../api/client";
import type { Canvas, PageContent, PageDetail, Section, SectionBackground, SectionLayout, WebPart, WebPartType } from "../api/types";
import { ErrorBar } from "../components/States";
import { LAYOUTS, layoutOf, sectionClass, useCanvasStyles } from "./CanvasView";
import { ImageUpload, WEB_PARTS, WebPartSettings, newWebPart } from "./WebParts";
import { PageBanner } from "./PageView";

const useStyles = makeStyles({
  bar: {
    position: "sticky", top: 0, zIndex: 2, display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap",
    padding: "6px 8px", marginBottom: "12px", borderRadius: tokens.borderRadiusLarge,
    backgroundColor: tokens.colorNeutralBackground1, boxShadow: tokens.shadow4,
  },
  status: { marginLeft: "auto", color: tokens.colorNeutralForeground3, paddingRight: "8px" },
  head: { display: "flex", flexDirection: "column", gap: "10px", marginBottom: "16px" },
  title: { fontSize: tokens.fontSizeHero700, fontWeight: tokens.fontWeightSemibold },
  sectionWrap: {
    display: "flex", flexDirection: "column", gap: "6px", padding: "8px",
    border: `1px dashed ${tokens.colorNeutralStroke1}`, borderRadius: tokens.borderRadiusLarge,
  },
  sectionBar: { display: "flex", alignItems: "center", gap: "6px", flexWrap: "wrap" },
  part: { display: "flex", flexDirection: "column", gap: "8px", padding: "10px", color: tokens.colorNeutralForeground1 },
  partBar: { display: "flex", alignItems: "center", gap: "2px" },
  partLabel: { flex: 1, color: tokens.colorNeutralForeground3 },
  small: { minWidth: "150px" },
});

const backgrounds: { value: SectionBackground; label: string }[] = [
  { value: "none", label: "No background" },
  { value: "soft", label: "Soft" },
  { value: "strong", label: "Strong" },
];

function move<T>(items: T[], from: number, to: number): T[] {
  if (to < 0 || to >= items.length) return items;
  const copy = [...items];
  const [x] = copy.splice(from, 1);
  copy.splice(to, 0, x);
  return copy;
}

/** Re-flows web parts when a section's layout changes: extra columns' parts move into the last column. */
function relayout(section: Section, layout: SectionLayout): Section {
  const n = layoutOf(layout).columns;
  const cols = section.columns.slice(0, n).map((c) => [...c]);
  while (cols.length < n) cols.push([]);
  section.columns.slice(n).forEach((c) => cols[n - 1].push(...c));
  return { ...section, layout, columns: cols };
}

/** Edit mode: title, banner, sections and web parts; saves drafts and publishes. */
export function PageEditor({ detail, onClose }: { detail: PageDetail; onClose: (published: boolean) => void }) {
  const s = useStyles();
  const cs = useCanvasStyles();
  const qc = useQueryClient();
  const siteId = detail.page.siteId;
  const start: PageContent = detail.draft ?? detail.published ?? { title: detail.page.title, canvas: { sections: [] } };
  const [content, setContent] = useState<PageContent>(start);
  const [revision, setRevision] = useState(detail.revision);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState<"save" | "publish" | "discard" | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [savedAt, setSavedAt] = useState<Date | null>(null);

  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  const update = (patch: Partial<PageContent>) => {
    setContent((c) => ({ ...c, ...patch }));
    setDirty(true);
  };
  const setCanvas = (fn: (c: Canvas) => Canvas) => {
    setContent((c) => ({ ...c, canvas: fn(c.canvas) }));
    setDirty(true);
  };
  const setSection = (i: number, fn: (sec: Section) => Section) =>
    setCanvas((c) => ({ sections: c.sections.map((sec, j) => (j === i ? fn(sec) : sec)) }));
  const setColumn = (i: number, col: number, fn: (parts: WebPart[]) => WebPart[]) =>
    setSection(i, (sec) => ({ ...sec, columns: sec.columns.map((p, k) => (k === col ? fn(p) : p)) }));

  const refresh = async () => {
    await Promise.all([
      qc.invalidateQueries({ queryKey: ["page"] }),
      qc.invalidateQueries({ queryKey: ["pages", siteId] }),
      qc.invalidateQueries({ queryKey: ["news", siteId] }),
    ]);
  };

  const save = async (): Promise<PageDetail> => {
    const saved = await api<PageDetail>(`/api/v1/pages/${detail.page.id}/draft`, {
      method: "PUT",
      body: { ...content, revision },
    });
    setRevision(saved.revision);
    if (saved.draft) setContent(saved.draft);   // the server's copy, with rich text sanitised
    setDirty(false);
    setSavedAt(new Date());
    return saved;
  };

  const run = async (kind: "save" | "publish" | "discard") => {
    setBusy(kind);
    setError(null);
    try {
      if (kind === "save") {
        await save();
        await refresh();
      } else if (kind === "publish") {
        await save();
        await api(`/api/v1/pages/${detail.page.id}/publish`, { method: "POST" });
        await refresh();
        onClose(true);
      } else {
        if (!window.confirm("Discard all unpublished changes to this page?")) return;
        await api(`/api/v1/pages/${detail.page.id}/discard`, { method: "POST" });
        await refresh();
        onClose(false);
      }
    } catch (err) {
      setError(err);
    } finally {
      setBusy(null);
    }
  };

  const close = () => {
    if (dirty && !window.confirm("You have unsaved changes. Close without saving?")) return;
    onClose(false);
  };

  const addSection = (layout: SectionLayout, at: number) =>
    setCanvas((c) => {
      const sections = [...c.sections];
      sections.splice(at, 0, { layout, background: "none", columns: Array.from({ length: layoutOf(layout).columns }, () => []) });
      return { sections };
    });

  const sectionMenu = (at: number, label: string) => (
    <Menu>
      <MenuTrigger disableButtonEnhancement>
        <Button icon={<AddRegular />} appearance="subtle">{label}</Button>
      </MenuTrigger>
      <MenuPopover>
        <MenuList>
          {LAYOUTS.map((l) => <MenuItem key={l.value} onClick={() => addSection(l.value, at)}>{l.label}</MenuItem>)}
        </MenuList>
      </MenuPopover>
    </Menu>
  );

  return (
    <div>
      <div className={s.bar} role="toolbar" aria-label="Page editing">
        <Button appearance="primary" icon={<SendRegular />} disabled={!!busy} onClick={() => run("publish")}>
          {busy === "publish" ? "Publishing…" : detail.page.published ? "Republish" : "Publish"}
        </Button>
        <Button icon={<SaveRegular />} disabled={!!busy || !dirty} onClick={() => run("save")}>
          {busy === "save" ? "Saving…" : "Save as draft"}
        </Button>
        {detail.page.published && (detail.page.hasDraft || dirty) && (
          <Button appearance="subtle" disabled={!!busy} onClick={() => run("discard")}>Discard changes</Button>
        )}
        <Button appearance="subtle" icon={<DismissRegular />} onClick={close}>Close</Button>
        <Text size={200} className={s.status} aria-live="polite">
          {dirty ? "Unsaved changes" : savedAt ? `Draft saved at ${savedAt.toLocaleTimeString()}` : detail.page.published ? "Published" : "Draft"}
        </Text>
      </div>
      <ErrorBar error={error} />
      {detail.page.kind === "NEWS" && (
        <MessageBar intent="info" style={{ marginBottom: 12 }}>
          <MessageBarBody>News posts appear in the site's News web parts once they are published.</MessageBarBody>
        </MessageBar>
      )}

      <div className={s.head}>
        <PageBanner imageId={content.bannerImageId} />
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          <ImageUpload siteId={siteId} hasImage={!!content.bannerImageId}
            label={content.bannerImageId ? "Change banner image" : "Add a banner image"}
            onUploaded={(id) => update({ bannerImageId: id })} />
          {content.bannerImageId && <Button appearance="subtle" onClick={() => update({ bannerImageId: undefined })}>Remove banner</Button>}
        </div>
        <Field label="Title" required>
          <Input className={s.title} value={content.title} onChange={(_, d) => update({ title: d.value })} />
        </Field>
        <Field label="Description" hint="Shown in news roll-ups and search previews">
          <Textarea value={content.description ?? ""} resize="vertical" onChange={(_, d) => update({ description: d.value })} />
        </Field>
      </div>

      <div className={cs.canvas}>
        {content.canvas.sections.map((section, i) => (
          <div key={i} className={s.sectionWrap}>
            <div className={s.sectionBar}>
              <Text weight="semibold" size={200}>Section {i + 1}</Text>
              <Dropdown className={s.small} size="small" aria-label="Section layout" value={layoutOf(section.layout).label}
                selectedOptions={[section.layout]} onOptionSelect={(_, d) => setSection(i, (sec) => relayout(sec, d.optionValue as SectionLayout))}>
                {LAYOUTS.map((l) => <Option key={l.value} value={l.value}>{l.label}</Option>)}
              </Dropdown>
              <Dropdown className={s.small} size="small" aria-label="Section background"
                value={backgrounds.find((b) => b.value === section.background)?.label}
                selectedOptions={[section.background]} onOptionSelect={(_, d) => setSection(i, (sec) => ({ ...sec, background: d.optionValue as SectionBackground }))}>
                {backgrounds.map((b) => <Option key={b.value} value={b.value}>{b.label}</Option>)}
              </Dropdown>
              <Toolbar size="small" style={{ marginLeft: "auto" }}>
                <Tooltip content="Move section up" relationship="label">
                  <ToolbarButton icon={<ArrowUpRegular />} disabled={i === 0} onClick={() => setCanvas((c) => ({ sections: move(c.sections, i, i - 1) }))} />
                </Tooltip>
                <Tooltip content="Move section down" relationship="label">
                  <ToolbarButton icon={<ArrowDownRegular />} disabled={i === content.canvas.sections.length - 1}
                    onClick={() => setCanvas((c) => ({ sections: move(c.sections, i, i + 1) }))} />
                </Tooltip>
                <Tooltip content="Delete section" relationship="label">
                  <ToolbarButton icon={<DeleteRegular />} onClick={() => {
                    if (section.columns.some((c) => c.length) && !window.confirm("Delete this section and its web parts?")) return;
                    setCanvas((c) => ({ sections: c.sections.filter((_, j) => j !== i) }));
                  }} />
                </Tooltip>
              </Toolbar>
            </div>
            <section className={sectionClass(cs, section)} style={{ gridTemplateColumns: layoutOf(section.layout).template }}>
              {section.columns.map((col, c) => (
                <div key={c} className={cs.column}>
                  {col.map((part, k) => (
                    <Card key={part.id} className={s.part}>
                      <div className={s.partBar}>
                        <Text size={200} weight="semibold" className={s.partLabel}>
                          {WEB_PARTS.find((w) => w.type === part.type)?.label}
                        </Text>
                        <Tooltip content="Move up" relationship="label">
                          <Button size="small" appearance="subtle" icon={<ArrowUpRegular />} disabled={k === 0}
                            onClick={() => setColumn(i, c, (p) => move(p, k, k - 1))} />
                        </Tooltip>
                        <Tooltip content="Move down" relationship="label">
                          <Button size="small" appearance="subtle" icon={<ArrowDownRegular />} disabled={k === col.length - 1}
                            onClick={() => setColumn(i, c, (p) => move(p, k, k + 1))} />
                        </Tooltip>
                        <Tooltip content="Delete web part" relationship="label">
                          <Button size="small" appearance="subtle" icon={<DeleteRegular />}
                            onClick={() => setColumn(i, c, (p) => p.filter((x) => x.id !== part.id))} />
                        </Tooltip>
                      </div>
                      <WebPartSettings part={part} siteId={siteId}
                        onChange={(props) => setColumn(i, c, (p) => p.map((x) => (x.id === part.id ? { ...x, props } : x)))} />
                    </Card>
                  ))}
                  <AddWebPart onAdd={(type) => setColumn(i, c, (p) => [...p, newWebPart(type)])} />
                </div>
              ))}
            </section>
          </div>
        ))}
        <div>{sectionMenu(content.canvas.sections.length, "Add section")}</div>
      </div>
    </div>
  );
}

function AddWebPart({ onAdd }: { onAdd: (type: WebPartType) => void }) {
  return (
    <Menu>
      <MenuTrigger disableButtonEnhancement>
        <Button icon={<AddRegular />} appearance="secondary" style={{ alignSelf: "flex-start" }}>Add web part</Button>
      </MenuTrigger>
      <MenuPopover>
        <MenuList>
          {WEB_PARTS.map((w) => (
            <MenuItem key={w.type} icon={w.icon} secondaryContent={undefined} onClick={() => onAdd(w.type)}>
              <div style={{ display: "flex", flexDirection: "column" }}>
                <span>{w.label}</span>
                <Text size={200} style={{ color: tokens.colorNeutralForeground3 }}>{w.description}</Text>
              </div>
            </MenuItem>
          ))}
        </MenuList>
      </MenuPopover>
    </Menu>
  );
}

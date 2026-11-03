import { useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  Button,
  Card,
  Dropdown,
  Field,
  Input,
  Option,
  SpinButton,
  Text,
  makeStyles,
  mergeClasses,
  tokens,
} from "@fluentui/react-components";
import {
  DocumentMultipleRegular,
  ImageAddRegular,
  ImageRegular,
  NewsRegular,
  TextBulletListSquareRegular,
  TextTRegular,
} from "@fluentui/react-icons";
import { api, contentUrl, postFile, routeFor } from "../api/client";
import type { ColumnDto, NodeDetail, NodeDto, PageSummary, WebPart, WebPartType } from "../api/types";
import { FileIcon } from "../components/FileIcon";
import { ColumnValue } from "../components/columns";
import { ErrorBar, Loading } from "../components/States";
import { formatDate, formatRelative } from "../components/format";
import { RichTextEditor, RichTextView } from "./RichText";

export const WEB_PARTS: { type: WebPartType; label: string; description: string; icon: React.ReactElement }[] = [
  { type: "text", label: "Text", description: "Paragraphs, headings, lists and links", icon: <TextTRegular /> },
  { type: "image", label: "Image", description: "A picture with a caption", icon: <ImageRegular /> },
  { type: "documents", label: "Documents", description: "Recent files from a document library", icon: <DocumentMultipleRegular /> },
  { type: "list", label: "List", description: "Items from one of the site's lists", icon: <TextBulletListSquareRegular /> },
  { type: "news", label: "News", description: "The site's latest news posts", icon: <NewsRegular /> },
];

export function newWebPart(type: WebPartType): WebPart {
  const id = `${type}-${Math.random().toString(36).slice(2, 10)}`;
  const props: Record<string, unknown> =
    type === "text" ? { html: "" }
    : type === "image" ? { fileId: null, alt: "", caption: "", size: "full" }
    : type === "news" ? { title: "News", limit: 4, layout: "cards" }
    : { title: "", limit: 8 };
  return { id, type, props };
}

const useStyles = makeStyles({
  part: { display: "flex", flexDirection: "column", gap: "8px", minWidth: 0 },
  head: { display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: "8px" },
  muted: { color: tokens.colorNeutralForeground3 },
  figure: { margin: 0, display: "flex", flexDirection: "column", gap: "6px" },
  img: { display: "block", width: "100%", height: "auto", borderRadius: tokens.borderRadiusLarge, objectFit: "cover" },
  placeholder: {
    display: "flex", alignItems: "center", justifyContent: "center", gap: "8px", minHeight: "120px",
    borderRadius: tokens.borderRadiusLarge, border: `1px dashed ${tokens.colorNeutralStroke1}`,
    color: tokens.colorNeutralForeground3,
  },
  rows: { display: "flex", flexDirection: "column" },
  row: {
    display: "flex", alignItems: "center", gap: "10px", padding: "8px 4px", textDecoration: "none",
    color: tokens.colorNeutralForeground1, borderBottom: `1px solid ${tokens.colorNeutralStroke3}`,
    ":hover": { backgroundColor: tokens.colorSubtleBackgroundHover },
  },
  rowMain: { display: "flex", flexDirection: "column", minWidth: 0, flex: 1 },
  ellipsis: { overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
  tableWrap: { overflowX: "auto" },
  table: {
    width: "100%", borderCollapse: "collapse", fontSize: tokens.fontSizeBase300,
    "& th": { textAlign: "left", fontWeight: tokens.fontWeightSemibold, padding: "6px 8px",
      borderBottom: `1px solid ${tokens.colorNeutralStroke2}`, whiteSpace: "nowrap" },
    "& td": { padding: "6px 8px", borderBottom: `1px solid ${tokens.colorNeutralStroke3}`, verticalAlign: "top" },
  },
  cards: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: "12px" },
  newsCard: { padding: 0, overflow: "hidden", height: "100%", textDecoration: "none" },
  newsImage: { width: "100%", aspectRatio: "16 / 9", objectFit: "cover", display: "block" },
  newsFallback: {
    width: "100%", aspectRatio: "16 / 9", display: "flex", alignItems: "center", justifyContent: "center",
    background: `linear-gradient(135deg, ${tokens.colorBrandBackground} 0%, ${tokens.colorBrandBackgroundPressed} 100%)`,
    color: tokens.colorNeutralForegroundOnBrand, fontSize: "32px",
  },
  newsBody: { display: "flex", flexDirection: "column", gap: "4px", padding: "10px 12px 12px" },
  clamp: { display: "-webkit-box", WebkitLineClamp: 3, WebkitBoxOrient: "vertical", overflow: "hidden" },
  newsRow: { display: "flex", gap: "12px", alignItems: "flex-start", textDecoration: "none", color: "inherit",
    padding: "8px 0", borderBottom: `1px solid ${tokens.colorNeutralStroke3}` },
  thumb: { width: "112px", flexShrink: 0, aspectRatio: "16 / 9", objectFit: "cover", borderRadius: tokens.borderRadiusMedium },
  settings: { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: "10px" },
});

const str = (v: unknown) => (typeof v === "string" ? v : "");
const num = (v: unknown, d: number) => (typeof v === "number" ? v : d);
const sizes: Record<string, string> = { small: "33%", medium: "50%", large: "75%", full: "100%" };

// ---- reading -----------------------------------------------------------------------------------

export function WebPartView({ part, siteId }: { part: WebPart; siteId: string }) {
  switch (part.type) {
    case "text":
      return str(part.props.html) ? <RichTextView html={str(part.props.html)} /> : null;
    case "image":
      return <ImagePart part={part} />;
    case "documents":
      return <DocumentsPart part={part} />;
    case "list":
      return <ListPart part={part} />;
    case "news":
      return <NewsPart part={part} siteId={siteId} />;
  }
}

function ImagePart({ part }: { part: WebPart }) {
  const s = useStyles();
  const fileId = str(part.props.fileId);
  if (!fileId) return <div className={s.placeholder}><ImageRegular /> No image chosen</div>;
  return (
    <figure className={s.figure} style={{ maxWidth: sizes[str(part.props.size)] ?? "100%" }}>
      <img src={contentUrl(fileId)} alt={str(part.props.alt)} className={s.img} loading="lazy" />
      {str(part.props.caption) && <Text as="span" size={200} className={s.muted}>{str(part.props.caption)}</Text>}
    </figure>
  );
}

function PartHead({ title, href }: { title: string; href?: string }) {
  const s = useStyles();
  return (
    <div className={s.head}>
      <Text as="h2" size={500} weight="semibold">{title}</Text>
      {href && <Link to={routeFor(href)}>See all</Link>}
    </div>
  );
}

function DocumentsPart({ part }: { part: WebPart }) {
  const s = useStyles();
  const libraryId = str(part.props.libraryId);
  const library = useQuery({
    queryKey: ["node", libraryId],
    queryFn: () => api<NodeDetail>(`/api/v1/nodes/${libraryId}`),
    enabled: !!libraryId,
    retry: false,
  });
  const children = useQuery({
    queryKey: ["children", libraryId],
    queryFn: () => api<NodeDto[]>(`/api/v1/nodes/${libraryId}/children`),
    enabled: !!libraryId,
    retry: false,
  });
  if (!libraryId) return <div className={s.placeholder}><DocumentMultipleRegular /> Choose a document library</div>;
  if (library.error || children.error) return null;   // no access, or the library was removed: show nothing
  const lib = library.data?.node;
  const files = [...(children.data ?? [])]
    .sort((a, b) => b.modifiedAt.localeCompare(a.modifiedAt))
    .slice(0, num(part.props.limit, 8));
  return (
    <section className={s.part} aria-label={str(part.props.title) || lib?.title || "Documents"}>
      <PartHead title={str(part.props.title) || lib?.title || lib?.name || "Documents"} href={lib?.path} />
      {children.isPending ? <Loading /> : !files.length ? <Text className={s.muted}>No documents yet.</Text> : (
        <div className={s.rows}>
          {files.map((f) => (
            <Link key={f.id} to={routeFor(f.path)} className={s.row}>
              <FileIcon kind={f.kind} name={f.name} />
              <div className={s.rowMain}>
                <Text className={s.ellipsis}>{f.name}</Text>
                <Text size={200} className={s.muted}>{f.modifiedBy?.displayName} · {formatRelative(f.modifiedAt)}</Text>
              </div>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}

function ListPart({ part }: { part: WebPart }) {
  const s = useStyles();
  const listId = str(part.props.listId);
  const list = useQuery({
    queryKey: ["node", listId], queryFn: () => api<NodeDetail>(`/api/v1/nodes/${listId}`), enabled: !!listId, retry: false,
  });
  const columns = useQuery({
    queryKey: ["columns", listId], queryFn: () => api<ColumnDto[]>(`/api/v1/nodes/${listId}/columns`), enabled: !!listId, retry: false,
  });
  const items = useQuery({
    queryKey: ["children", listId], queryFn: () => api<NodeDto[]>(`/api/v1/nodes/${listId}/children`), enabled: !!listId, retry: false,
  });
  if (!listId) return <div className={s.placeholder}><TextBulletListSquareRegular /> Choose a list</div>;
  if (list.error || items.error) return null;
  const node = list.data?.node;
  const cols = (columns.data ?? []).filter((c) => c.type !== "NOTE").slice(0, 3);
  const rows = [...(items.data ?? [])]
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .slice(0, num(part.props.limit, 8));
  return (
    <section className={s.part} aria-label={str(part.props.title) || node?.title || "List"}>
      <PartHead title={str(part.props.title) || node?.title || node?.name || "List"} href={node?.path} />
      {items.isPending ? <Loading /> : !rows.length ? <Text className={s.muted}>No items yet.</Text> : (
        <div className={s.tableWrap}>
          <table className={s.table}>
            <thead>
              <tr>
                <th>Title</th>
                {cols.map((c) => <th key={c.id}>{c.displayName}</th>)}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td><Link to={routeFor(r.path)}>{r.title || r.name}</Link></td>
                  {cols.map((c) => <td key={c.id}><ColumnValue column={c} value={r.metadata?.[c.internalName]} /></td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

export function NewsPart({ part, siteId }: { part: WebPart; siteId: string }) {
  const s = useStyles();
  const limit = num(part.props.limit, 4);
  const news = useQuery({
    queryKey: ["news", siteId, limit],
    queryFn: () => api<PageSummary[]>(`/api/v1/sites/${siteId}/news?limit=${limit}`),
  });
  const title = str(part.props.title) || "News";
  return (
    <section className={s.part} aria-label={title}>
      <PartHead title={title} />
      <ErrorBar error={news.error} />
      {news.isPending ? <Loading /> : !news.data?.length ? <Text className={s.muted}>No news yet.</Text>
        : part.props.layout === "list" ? (
          <div className={s.rows}>
            {news.data.map((n) => (
              <Link key={n.id} to={routeFor(n.url)} className={s.newsRow}>
                {n.bannerImageId
                  ? <img src={contentUrl(n.bannerImageId)} alt="" className={s.thumb} loading="lazy" />
                  : <div className={mergeClasses(s.newsFallback, s.thumb)} style={{ fontSize: 20 }}><NewsRegular /></div>}
                <div className={s.rowMain}>
                  <Text weight="semibold">{n.title}</Text>
                  {n.excerpt && <Text size={200} className={s.clamp}>{n.excerpt}</Text>}
                  <Text size={200} className={s.muted}>{n.author?.displayName} · {formatDate(n.publishedAt)}</Text>
                </div>
              </Link>
            ))}
          </div>
        ) : (
          <div className={s.cards}>
            {news.data.map((n) => (
              <Link key={n.id} to={routeFor(n.url)} style={{ textDecoration: "none" }}>
                <Card className={s.newsCard}>
                  {n.bannerImageId
                    ? <img src={contentUrl(n.bannerImageId)} alt="" className={s.newsImage} loading="lazy" />
                    : <div className={s.newsFallback}><NewsRegular /></div>}
                  <div className={s.newsBody}>
                    <Text weight="semibold" size={400}>{n.title}</Text>
                    {n.excerpt && <Text size={200} className={s.clamp}>{n.excerpt}</Text>}
                    <Text size={200} className={s.muted}>{n.author?.displayName} · {formatDate(n.publishedAt)}</Text>
                  </div>
                </Card>
              </Link>
            ))}
          </div>
        )}
    </section>
  );
}

// ---- editing -----------------------------------------------------------------------------------

/** The settings of one web part while the page is in edit mode. Text is edited in place. */
export function WebPartSettings(p: { part: WebPart; siteId: string; onChange: (props: Record<string, unknown>) => void }) {
  const s = useStyles();
  const set = (patch: Record<string, unknown>) => p.onChange({ ...p.part.props, ...patch });
  const { props } = p.part;

  switch (p.part.type) {
    case "text":
      return <RichTextEditor html={str(props.html)} label="Text" onChange={(html) => set({ html })} />;
    case "image":
      return (
        <div className={s.part}>
          <ImagePart part={p.part} />
          <ImageUpload siteId={p.siteId} onUploaded={(id) => set({ fileId: id })} hasImage={!!str(props.fileId)} />
          <div className={s.settings}>
            <Field label="Alternative text" hint="Describes the image for screen readers">
              <Input value={str(props.alt)} onChange={(_, d) => set({ alt: d.value })} />
            </Field>
            <Field label="Caption">
              <Input value={str(props.caption)} onChange={(_, d) => set({ caption: d.value })} />
            </Field>
            <Field label="Size">
              <Dropdown value={{ small: "Small", medium: "Medium", large: "Large", full: "Full width" }[str(props.size) || "full"]}
                selectedOptions={[str(props.size) || "full"]} onOptionSelect={(_, d) => set({ size: d.optionValue })}>
                <Option value="small">Small</Option>
                <Option value="medium">Medium</Option>
                <Option value="large">Large</Option>
                <Option value="full">Full width</Option>
              </Dropdown>
            </Field>
          </div>
        </div>
      );
    case "documents":
    case "list":
      return <SourceSettings {...p} />;
    case "news":
      return (
        <div className={s.part}>
          <div className={s.settings}>
            <Field label="Title"><Input value={str(props.title)} onChange={(_, d) => set({ title: d.value })} /></Field>
            <Field label="Number of posts">
              <SpinButton min={1} max={20} value={num(props.limit, 4)} onChange={(_, d) => set({ limit: (d.value ?? Number(d.displayValue)) || 4 })} />
            </Field>
            <Field label="Layout">
              <Dropdown value={props.layout === "list" ? "List" : "Cards"} selectedOptions={[str(props.layout) || "cards"]}
                onOptionSelect={(_, d) => set({ layout: d.optionValue })}>
                <Option value="cards">Cards</Option>
                <Option value="list">List</Option>
              </Dropdown>
            </Field>
          </div>
          <NewsPart part={p.part} siteId={p.siteId} />
        </div>
      );
  }
}

function SourceSettings(p: { part: WebPart; siteId: string; onChange: (props: Record<string, unknown>) => void }) {
  const s = useStyles();
  const isList = p.part.type === "list";
  const key = isList ? "listId" : "libraryId";
  const sources = useQuery({
    queryKey: [isList ? "lists" : "libraries", p.siteId],
    queryFn: () => api<NodeDto[]>(`/api/v1/sites/${p.siteId}/${isList ? "lists" : "libraries"}`),
  });
  const { props } = p.part;
  const set = (patch: Record<string, unknown>) => p.onChange({ ...props, ...patch });
  const selected = sources.data?.find((n) => n.id === props[key]);
  return (
    <div className={s.part}>
      <div className={s.settings}>
        <Field label={isList ? "List" : "Document library"} required>
          <Dropdown placeholder={isList ? "Choose a list" : "Choose a library"} value={selected ? selected.title ?? selected.name : ""}
            selectedOptions={selected ? [selected.id] : []} onOptionSelect={(_, d) => set({ [key]: d.optionValue })}>
            {(sources.data ?? []).map((n) => <Option key={n.id} value={n.id}>{n.title ?? n.name}</Option>)}
          </Dropdown>
        </Field>
        <Field label="Title" hint="Leave empty to use the name">
          <Input value={str(props.title)} onChange={(_, d) => set({ title: d.value })} />
        </Field>
        <Field label={isList ? "Number of items" : "Number of documents"}>
          <SpinButton min={1} max={50} value={num(props.limit, 8)} onChange={(_, d) => set({ limit: (d.value ?? Number(d.displayValue)) || 8 })} />
        </Field>
      </div>
      <WebPartView part={p.part} siteId={p.siteId} />
    </div>
  );
}

/** Uploads an image to the site's "Site Assets" library. */
export function ImageUpload(p: { siteId: string; onUploaded: (fileId: string) => void; hasImage: boolean; label?: string }) {
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  return (
    <div>
      <ErrorBar error={error} />
      <input ref={input} type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden
        onChange={async (e) => {
          const f = e.target.files?.[0];
          e.target.value = "";
          if (!f) return;
          setBusy(true);
          setError(null);
          try {
            const node = await postFile<NodeDto>(`/api/v1/sites/${p.siteId}/page-images`, f);
            p.onUploaded(node.id);
          } catch (err) {
            setError(err);
          } finally {
            setBusy(false);
          }
        }} />
      <Button icon={<ImageAddRegular />} disabled={busy} onClick={() => input.current?.click()}>
        {busy ? "Uploading…" : p.label ?? (p.hasImage ? "Change image" : "Upload image")}
      </Button>
    </div>
  );
}

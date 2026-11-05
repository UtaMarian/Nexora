import { createContext, useContext, useRef, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  Button,
  Card,
  Dropdown,
  Field,
  Input,
  Tooltip,
  Option,
  Persona,
  SpinButton,
  Text,
  makeStyles,
  mergeClasses,
  tokens,
} from "@fluentui/react-components";
import {
  ArrowDownRegular,
  ArrowUpRegular,
  CalendarRegular,
  DismissRegular,
  DocumentMultipleRegular,
  GlobeVideoRegular,
  ImageAddRegular,
  ImageRegular,
  LinkMultipleRegular,
  NewsRegular,
  PeopleRegular,
  TextBulletListSquareRegular,
  TextTRegular,
} from "@fluentui/react-icons";
import { api, contentUrl, postFile, routeFor } from "../api/client";
import type { ColumnDto, DesignResponse, Link as SiteLink, NodeDetail, NodeDto, PageSummary, WebPart, WebPartType } from "../api/types";
import { FileIcon } from "../components/FileIcon";
import { ColumnValue } from "../components/columns";
import { ErrorBar, Loading } from "../components/States";
import { formatDate, formatRelative } from "../components/format";
import { LinkEditor } from "../dialogs/LinkEditor";
import { PeoplePicker } from "../dialogs/PeoplePicker";
import { RichTextEditor, RichTextView } from "./RichText";

export const WEB_PARTS: { type: WebPartType; label: string; description: string; icon: React.ReactElement }[] = [
  { type: "text", label: "Text", description: "Paragraphs, headings, lists and links", icon: <TextTRegular /> },
  { type: "image", label: "Image", description: "A picture with a caption", icon: <ImageRegular /> },
  { type: "documents", label: "Documents", description: "Recent files from a document library", icon: <DocumentMultipleRegular /> },
  { type: "list", label: "List", description: "Items from one of the site's lists", icon: <TextBulletListSquareRegular /> },
  { type: "news", label: "News", description: "The latest news from this site or its hub", icon: <NewsRegular /> },
  { type: "quicklinks", label: "Quick links", description: "Tiles or a list of useful links", icon: <LinkMultipleRegular /> },
  { type: "events", label: "Events", description: "What is coming up, from a list with dates", icon: <CalendarRegular /> },
  { type: "people", label: "People", description: "The people to know, and what they do", icon: <PeopleRegular /> },
  { type: "embed", label: "Embed", description: "A video, map or form from an allowed site", icon: <GlobeVideoRegular /> },
];

export function newWebPart(type: WebPartType): WebPart {
  const id = `${type}-${Math.random().toString(36).slice(2, 10)}`;
  const props: Record<string, unknown> =
    type === "text" ? { html: "" }
    : type === "image" ? { fileId: null, alt: "", caption: "", size: "full" }
    : type === "news" ? { title: "News", limit: 4, layout: "cards", scope: "site" }
    : type === "quicklinks" ? { title: "Quick links", layout: "tiles", links: [] }
    : type === "events" ? { title: "Upcoming events", limit: 5 }
    : type === "people" ? { title: "People", people: [] }
    : type === "embed" ? { url: "", title: "", height: 420 }
    : { title: "", limit: 8 };
  return { id, type, props };
}

const useStyles = makeStyles({
  part: { display: "flex", flexDirection: "column", gap: "8px", minWidth: 0 },
  head: { display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: "8px" },
  muted: { color: tokens.colorNeutralForeground3 },
  link: { color: tokens.colorBrandForeground1 },
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
  tiles: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(170px, 1fr))", gap: "10px" },
  tile: {
    display: "flex", flexDirection: "column", gap: "2px", padding: "12px", textDecoration: "none", minHeight: "64px",
    borderRadius: tokens.borderRadiusLarge, border: `1px solid ${tokens.colorNeutralStroke2}`,
    backgroundColor: tokens.colorNeutralBackground1, color: tokens.colorBrandForeground1,
    ":hover": { backgroundColor: tokens.colorSubtleBackgroundHover },
  },
  dateTile: {
    display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", width: "48px", height: "48px",
    flexShrink: 0, borderRadius: tokens.borderRadiusMedium, lineHeight: 1.1,
    backgroundColor: tokens.colorBrandBackground2, color: tokens.colorBrandForeground2,
  },
  people: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(200px, 1fr))", gap: "12px" },
  personRow: { display: "flex", alignItems: "center", gap: "6px", flexWrap: "wrap", marginBottom: "6px" },
  personName: { width: "170px", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
  frame: { display: "block", width: "100%", border: "none", borderRadius: tokens.borderRadiusLarge, backgroundColor: tokens.colorNeutralBackground3 },
});

/** True while a page is being edited: only then do empty web parts ask to be filled in. */
export const EditingParts = createContext(false);

function Placeholder({ children }: { children: ReactNode }) {
  const s = useStyles();
  return useContext(EditingParts) ? <div className={s.placeholder}>{children}</div> : null;
}

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
    case "quicklinks":
      return <QuickLinksPart part={part} />;
    case "events":
      return <EventsPart part={part} />;
    case "people":
      return <PeoplePart part={part} />;
    case "embed":
      return <EmbedPart part={part} />;
  }
}

function QuickLinksPart({ part }: { part: WebPart }) {
  const s = useStyles();
  const links = (part.props.links ?? []) as SiteLink[];
  const title = str(part.props.title);
  if (!links.length) return <Placeholder><LinkMultipleRegular /> Add some links</Placeholder>;
  const inner = (l: SiteLink) => (
    <>
      <Text weight="semibold" style={{ color: "inherit" }}>{l.label}</Text>
      {l.description && <Text size={200} className={s.muted}>{l.description}</Text>}
    </>
  );
  const className = part.props.layout === "list" ? s.row : s.tile;
  return (
    <section className={s.part} aria-label={title || "Quick links"}>
      {title && <PartHead title={title} />}
      <div className={part.props.layout === "list" ? s.rows : s.tiles}>
        {links.map((l, i) => (l.url ?? "").startsWith("/") ? (
          <Link key={i} to={routeFor(l.url!)} className={className}><div className={s.rowMain}>{inner(l)}</div></Link>
        ) : (
          <a key={i} href={l.url} target="_blank" rel="noopener noreferrer" className={className}><div className={s.rowMain}>{inner(l)}</div></a>
        ))}
      </div>
    </section>
  );
}

/** Today in the viewer's calendar, in the form date columns are stored. */
const todayIso = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};

function EventsPart({ part }: { part: WebPart }) {
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
  if (!listId) return <Placeholder><CalendarRegular /> Choose a list of events</Placeholder>;
  if (list.error || items.error) return null;
  const node = list.data?.node;
  const title = str(part.props.title) || node?.title || "Events";
  const cols = columns.data ?? [];
  const dateColumn = str(part.props.dateColumn) || cols.find((c) => c.type === "DATE")?.internalName;
  const location = cols.find((c) => c.displayName.toLowerCase() === "location")?.internalName;
  const dateOf = (n: NodeDto) => String((dateColumn && n.metadata?.[dateColumn]) ?? "").slice(0, 10);
  const today = todayIso();
  const upcoming = (items.data ?? [])
    .filter((n) => dateOf(n) >= today)
    .sort((a, b) => dateOf(a).localeCompare(dateOf(b)))
    .slice(0, num(part.props.limit, 5));
  return (
    <section className={s.part} aria-label={title}>
      <PartHead title={title} href={node?.path} />
      {items.isPending || columns.isPending ? <Loading />
        : !dateColumn ? <Text className={s.muted}>This list has no date column.</Text>
        : !upcoming.length ? <Text className={s.muted}>Nothing coming up.</Text> : (
          <div className={s.rows}>
            {upcoming.map((n) => {
              const when = new Date(`${dateOf(n)}T00:00:00`);
              return (
                <Link key={n.id} to={routeFor(n.path)} className={s.row}>
                  <div className={s.dateTile} aria-hidden>
                    <Text size={100}>{when.toLocaleDateString(undefined, { month: "short" })}</Text>
                    <Text size={400} weight="semibold" style={{ color: "inherit" }}>{when.getDate()}</Text>
                  </div>
                  <div className={s.rowMain}>
                    <Text className={s.ellipsis}>{n.title || n.name}</Text>
                    <Text size={200} className={s.muted}>
                      {when.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" })}
                      {location && n.metadata?.[location] ? ` · ${String(n.metadata[location])}` : ""}
                    </Text>
                  </div>
                </Link>
              );
            })}
          </div>
        )}
    </section>
  );
}

interface PersonEntry { login: string; displayName: string; role: string }

function PeoplePart({ part }: { part: WebPart }) {
  const s = useStyles();
  const people = (part.props.people ?? []) as PersonEntry[];
  const title = str(part.props.title);
  if (!people.length) return <Placeholder><PeopleRegular /> Add people</Placeholder>;
  return (
    <section className={s.part} aria-label={title || "People"}>
      {title && <PartHead title={title} />}
      <div className={s.people}>
        {people.map((p, i) => (
          <Persona key={i} name={p.displayName || p.login} secondaryText={p.role} avatar={{ color: "colorful" }} size="large" />
        ))}
      </div>
    </section>
  );
}

function EmbedPart({ part }: { part: WebPart }) {
  const s = useStyles();
  const url = str(part.props.url);
  const title = str(part.props.title);
  const hosts = useQuery({ queryKey: ["embed-hosts"], queryFn: () => api<string[]>("/api/v1/embed-hosts"), staleTime: 300000 });
  if (!url) return <Placeholder><GlobeVideoRegular /> Enter an address to embed</Placeholder>;
  if (hosts.isPending) return <Loading />;
  let allowed = false;
  try {
    const u = new URL(url);
    allowed = u.protocol === "https:" && !!hosts.data?.includes(u.hostname.toLowerCase());
  } catch {
    allowed = false;
  }
  // an administrator may have withdrawn the site since the page was saved
  if (!allowed) return <div className={s.placeholder}><GlobeVideoRegular /> This content is not on the list of allowed sites</div>;
  return (
    <section className={s.part} aria-label={title || "Embedded content"}>
      {title && <PartHead title={title} />}
      <iframe src={url} title={title || "Embedded content"} className={s.frame} height={num(part.props.height, 420)} loading="lazy"
        sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-presentation"
        referrerPolicy="strict-origin-when-cross-origin" allow="fullscreen; picture-in-picture" />
    </section>
  );
}

function ImagePart({ part }: { part: WebPart }) {
  const s = useStyles();
  const fileId = str(part.props.fileId);
  if (!fileId) return <Placeholder><ImageRegular /> No image chosen</Placeholder>;
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
      {href && <Link to={routeFor(href)} className={s.link}>See all</Link>}
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
  if (!libraryId) return <Placeholder><DocumentMultipleRegular /> Choose a document library</Placeholder>;
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
  if (!listId) return <Placeholder><TextBulletListSquareRegular /> Choose a list</Placeholder>;
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
                  <td><Link to={routeFor(r.path)} className={s.link}>{r.title || r.name}</Link></td>
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
  const scope = part.props.scope === "hub" ? "hub" : "site";
  const news = useQuery({
    queryKey: ["news", siteId, limit, scope],
    queryFn: () => api<PageSummary[]>(`/api/v1/sites/${siteId}/news?limit=${limit}&scope=${scope}`),
  });
  const byline = (n: PageSummary) =>
    [scope === "hub" ? n.siteTitle : null, n.author?.displayName, formatDate(n.publishedAt)].filter(Boolean).join(" · ");
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
                  <Text size={200} className={s.muted}>{byline(n)}</Text>
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
                    <Text size={200} className={s.muted}>{byline(n)}</Text>
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
            <NewsScope siteId={p.siteId} value={str(props.scope) || "site"} onChange={(scope) => set({ scope })} />
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
    case "quicklinks":
      return <QuickLinksSettings {...p} />;
    case "events":
      return <EventsSettings {...p} />;
    case "people":
      return <PeopleSettings {...p} />;
    case "embed":
      return (
        <div className={s.part}>
          <div className={s.settings}>
            <Field label="Address" hint="A YouTube or Vimeo link, or any page on an allowed site. Checked when you save.">
              <Input type="url" value={str(props.url)} placeholder="https://" onChange={(_, d) => set({ url: d.value })} />
            </Field>
            <Field label="Title" hint="Screen readers announce this">
              <Input value={str(props.title)} onChange={(_, d) => set({ title: d.value })} />
            </Field>
            <Field label="Height in pixels">
              <SpinButton min={200} max={1200} step={20} value={num(props.height, 420)}
                onChange={(_, d) => set({ height: (d.value ?? Number(d.displayValue)) || 420 })} />
            </Field>
          </div>
          <EmbedPart part={p.part} />
        </div>
      );
  }
}

type SettingsProps = { part: WebPart; siteId: string; onChange: (props: Record<string, unknown>) => void };

/** Only offered in a site that belongs to a hub. */
function NewsScope(p: { siteId: string; value: string; onChange: (scope: string) => void }) {
  const design = useQuery({ queryKey: ["design", p.siteId], queryFn: () => api<DesignResponse>(`/api/v1/sites/${p.siteId}/design`) });
  if (!design.data?.hub?.id) return null;
  return (
    <Field label="News from">
      <Dropdown value={p.value === "hub" ? "All sites in the hub" : "This site"} selectedOptions={[p.value]}
        onOptionSelect={(_, d) => p.onChange(d.optionValue ?? "site")}>
        <Option value="site">This site</Option>
        <Option value="hub">All sites in the hub</Option>
      </Dropdown>
    </Field>
  );
}

function QuickLinksSettings(p: SettingsProps) {
  const s = useStyles();
  const { props } = p.part;
  const set = (patch: Record<string, unknown>) => p.onChange({ ...props, ...patch });
  const libraries = useQuery({ queryKey: ["libraries", p.siteId], queryFn: () => api<NodeDto[]>(`/api/v1/sites/${p.siteId}/libraries`) });
  const lists = useQuery({ queryKey: ["lists", p.siteId], queryFn: () => api<NodeDto[]>(`/api/v1/sites/${p.siteId}/lists`) });
  return (
    <div className={s.part}>
      <div className={s.settings}>
        <Field label="Title"><Input value={str(props.title)} onChange={(_, d) => set({ title: d.value })} /></Field>
        <Field label="Layout">
          <Dropdown value={props.layout === "list" ? "List" : "Tiles"} selectedOptions={[str(props.layout) || "tiles"]}
            onOptionSelect={(_, d) => set({ layout: d.optionValue })}>
            <Option value="tiles">Tiles</Option>
            <Option value="list">List</Option>
          </Dropdown>
        </Field>
      </div>
      <LinkEditor links={(props.links ?? []) as SiteLink[]} max={24} descriptions onChange={(links) => set({ links })}
        siteContent={[...(libraries.data ?? []), ...(lists.data ?? [])]} />
    </div>
  );
}

function EventsSettings(p: SettingsProps) {
  const s = useStyles();
  const { props } = p.part;
  const set = (patch: Record<string, unknown>) => p.onChange({ ...props, ...patch });
  const lists = useQuery({ queryKey: ["lists", p.siteId], queryFn: () => api<NodeDto[]>(`/api/v1/sites/${p.siteId}/lists`) });
  const listId = str(props.listId);
  const columns = useQuery({
    queryKey: ["columns", listId], queryFn: () => api<ColumnDto[]>(`/api/v1/nodes/${listId}/columns`), enabled: !!listId,
  });
  const selected = lists.data?.find((n) => n.id === listId);
  const dates = (columns.data ?? []).filter((c) => c.type === "DATE");
  const dateColumn = dates.find((c) => c.internalName === props.dateColumn) ?? dates[0];
  return (
    <div className={s.part}>
      <div className={s.settings}>
        <Field label="List" required>
          <Dropdown placeholder="Choose a list" value={selected ? selected.title ?? selected.name : ""}
            selectedOptions={selected ? [selected.id] : []} onOptionSelect={(_, d) => set({ listId: d.optionValue, dateColumn: "" })}>
            {(lists.data ?? []).map((n) => <Option key={n.id} value={n.id}>{n.title ?? n.name}</Option>)}
          </Dropdown>
        </Field>
        {dates.length > 1 && (
          <Field label="Date column">
            <Dropdown value={dateColumn?.displayName ?? ""} selectedOptions={dateColumn ? [dateColumn.internalName] : []}
              onOptionSelect={(_, d) => set({ dateColumn: d.optionValue })}>
              {dates.map((c) => <Option key={c.id} value={c.internalName}>{c.displayName}</Option>)}
            </Dropdown>
          </Field>
        )}
        <Field label="Title"><Input value={str(props.title)} onChange={(_, d) => set({ title: d.value })} /></Field>
        <Field label="Number of events">
          <SpinButton min={1} max={20} value={num(props.limit, 5)} onChange={(_, d) => set({ limit: (d.value ?? Number(d.displayValue)) || 5 })} />
        </Field>
      </div>
      <EventsPart part={p.part} />
    </div>
  );
}

function PeopleSettings(p: SettingsProps) {
  const s = useStyles();
  const { props } = p.part;
  const people = (props.people ?? []) as PersonEntry[];
  const setPeople = (next: PersonEntry[]) => p.onChange({ ...props, people: next });
  const move = (i: number, by: number) => {
    const next = [...people];
    const [x] = next.splice(i, 1);
    next.splice(i + by, 0, x);
    setPeople(next);
  };
  return (
    <div className={s.part}>
      <Field label="Title"><Input value={str(props.title)} onChange={(_, d) => p.onChange({ ...props, title: d.value })} /></Field>
      {people.map((m, i) => (
        <div key={m.login} className={s.personRow}>
          <Text className={s.personName}>{m.displayName || m.login}</Text>
          <Input style={{ flex: 1, minWidth: 140 }} value={m.role} placeholder="Role" maxLength={120} aria-label={`Role of ${m.displayName}`}
            onChange={(_, d) => setPeople(people.map((x, j) => (j === i ? { ...x, role: d.value } : x)))} />
          <Tooltip content="Move up" relationship="label">
            <Button appearance="subtle" icon={<ArrowUpRegular />} disabled={i === 0} onClick={() => move(i, -1)} />
          </Tooltip>
          <Tooltip content="Move down" relationship="label">
            <Button appearance="subtle" icon={<ArrowDownRegular />} disabled={i === people.length - 1} onClick={() => move(i, 1)} />
          </Tooltip>
          <Tooltip content="Remove" relationship="label">
            <Button appearance="subtle" icon={<DismissRegular />} onClick={() => setPeople(people.filter((_, j) => j !== i))} />
          </Tooltip>
        </div>
      ))}
      {people.length < 24 && (
        <Field label="Add a person">
          <PeoplePicker usersOnly placeholder="Enter a name"
            onSelect={(x) => {
              if (x && !people.some((m) => m.login === x.login)) setPeople([...people, { login: x.login, displayName: x.displayName, role: "" }]);
            }} />
        </Field>
      )}
    </div>
  );
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

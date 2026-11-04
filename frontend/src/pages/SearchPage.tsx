import { useMemo } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useInfiniteQuery } from "@tanstack/react-query";
import {
  Button, Card, Dropdown, MessageBar, MessageBarBody, Option, Text, makeStyles, mergeClasses, tokens,
} from "@fluentui/react-components";
import { DismissRegular, NewsRegular, DocumentOnePageRegular } from "@fluentui/react-icons";
import { ApiError, api, routeFor } from "../api/client";
import type { Kind, SearchFacetValue, SearchHit, SearchResponse } from "../api/types";
import { FileIcon } from "../components/FileIcon";
import { Empty, ErrorBar, Loading } from "../components/States";
import { useMe } from "../components/me";
import { formatRelative, formatSize } from "../components/format";

const PAGE_SIZE = 20;

const TYPE_LABELS: Record<string, string> = {
  word: "Word", excel: "Excel", powerpoint: "PowerPoint", pdf: "PDF", image: "Images", video: "Video", audio: "Audio",
  text: "Text files", file: "Other files", folder: "Folders", library: "Libraries", list: "Lists", item: "List items",
  page: "Pages", news: "News", site: "Sites",
};
const MODIFIED_LABELS: Record<string, string> = {
  day: "Past 24 hours", week: "Past week", month: "Past month", year: "Past year",
};
const FACETS = [
  { key: "type", title: "Type", label: (f: SearchFacetValue) => TYPE_LABELS[f.value] ?? f.value },
  { key: "site", title: "Site", label: (f: SearchFacetValue) => f.label },
  { key: "author", title: "Author", label: (f: SearchFacetValue) => f.label },
  { key: "modified", title: "Modified", label: (f: SearchFacetValue) => MODIFIED_LABELS[f.value] ?? f.value },
];

const useStyles = makeStyles({
  layout: {
    display: "grid", gridTemplateColumns: "minmax(0, 1fr) 240px", gap: "24px", alignItems: "start",
    "@media (max-width: 820px)": { gridTemplateColumns: "minmax(0, 1fr)" },
  },
  summary: { display: "flex", alignItems: "center", gap: "12px", justifyContent: "space-between", flexWrap: "wrap", marginBottom: "12px" },
  muted: { color: tokens.colorNeutralForeground3 },
  list: { display: "flex", flexDirection: "column", gap: "8px" },
  item: { display: "flex", gap: "12px", alignItems: "flex-start", textDecoration: "none", color: "inherit" },
  body: { minWidth: 0 },
  path: { color: tokens.colorNeutralForeground3, overflowWrap: "anywhere" },
  snippet: { display: "block", margin: "4px 0", overflowWrap: "anywhere" },
  match: { backgroundColor: tokens.colorPaletteYellowBackground2, color: "inherit", fontWeight: tokens.fontWeightSemibold },
  facets: {
    display: "flex", flexDirection: "column", gap: "16px", position: "sticky", top: "8px",
    "@media (max-width: 820px)": { position: "static", order: -1, flexDirection: "row", flexWrap: "wrap" },
  },
  facet: { display: "flex", flexDirection: "column", gap: "2px", minWidth: "160px" },
  value: {
    display: "flex", justifyContent: "space-between", gap: "8px", padding: "4px 8px", border: "none", cursor: "pointer",
    textAlign: "left", backgroundColor: "transparent", color: tokens.colorNeutralForeground1, font: "inherit",
    borderRadius: tokens.borderRadiusMedium, ":hover": { backgroundColor: tokens.colorSubtleBackgroundHover },
  },
  selected: { backgroundColor: tokens.colorBrandBackground2, color: tokens.colorBrandForeground2, fontWeight: tokens.fontWeightSemibold },
  more: { marginTop: "12px", alignSelf: "center" },
});

function HitIcon({ hit }: { hit: SearchHit }) {
  const style = { fontSize: 20, flexShrink: 0 };
  if (hit.kind === "NEWS") return <NewsRegular style={style} />;
  if (hit.kind === "PAGE") return <DocumentOnePageRegular style={style} />;
  return <FileIcon kind={hit.kind as Kind} name={hit.name} />;
}

function Result({ hit }: { hit: SearchHit }) {
  const s = useStyles();
  return (
    <Card>
      <Link to={routeFor(hit.path)} className={s.item}>
        <HitIcon hit={hit} />
        <div className={s.body}>
          <Text weight="semibold" block>{hit.title}</Text>
          {hit.snippet.length > 0 && (
            <Text size={300} className={s.snippet}>
              {hit.snippet.map((seg, i) => (seg.match ? <mark key={i} className={s.match}>{seg.text}</mark> : seg.text))}
            </Text>
          )}
          <Text size={200} className={s.path} block>{hit.path}</Text>
          <Text size={200} className={s.muted} block>
            {[hit.siteTitle, hit.modifiedBy && `Modified ${formatRelative(hit.modifiedAt)} by ${hit.modifiedBy}`,
              hit.sizeBytes ? formatSize(hit.sizeBytes) : null].filter(Boolean).join(" · ")}
          </Text>
        </div>
      </Link>
    </Card>
  );
}

export function SearchPage() {
  const s = useStyles();
  const me = useMe();
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const sort = params.get("sort") === "modified" ? "modified" : "relevance";
  const filters = useMemo(() => FACETS.flatMap((f) => (params.get(f.key) ? [[f.key, params.get(f.key)!]] : [])), [params]);
  const active = q.length > 0 || filters.length > 0;

  const results = useInfiniteQuery({
    queryKey: ["search", q, sort, filters],
    initialPageParam: 0,
    getNextPageParam: (last: SearchResponse, all) => (all.length * PAGE_SIZE < last.total && last.hits.length > 0 ? all.length : undefined),
    queryFn: ({ pageParam }) => {
      const query = new URLSearchParams({ q, sort, page: String(pageParam), size: String(PAGE_SIZE) });
      filters.forEach(([key, value]) => query.append(key, value));
      return api<SearchResponse>(`/api/v1/search?${query}`);
    },
    enabled: active && me.searchEnabled,
  });

  // the error covers a tab opened before an administrator turned search off
  if (!me.searchEnabled || (results.error instanceof ApiError && results.error.code === "search_disabled")) {
    return <Empty title="Search is turned off"><Text>An administrator has turned search off.</Text></Empty>;
  }

  const first = results.data?.pages[0];
  const hits = results.data?.pages.flatMap((p) => p.hits) ?? [];

  const setParam = (key: string, value?: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  return (
    <>
      <Text as="h1" size={700} weight="semibold" block style={{ marginBottom: 12 }}>
        {q ? `Results for “${q}”` : "Search"}
      </Text>
      <ErrorBar error={results.error} />
      {first?.degraded && (
        <MessageBar intent="warning" style={{ marginBottom: 12 }}>
          <MessageBarBody>Full-text search is unavailable right now, so these results only match names.</MessageBarBody>
        </MessageBar>
      )}
      {!active ? (
        <Empty title="Search for something">
          <Text>Type in the search box above. Search looks inside documents, lists and pages you have access to.</Text>
        </Empty>
      ) : results.isPending ? (
        <Loading />
      ) : (
        <div className={s.layout}>
          <div className={s.list}>
            <div className={s.summary}>
              <Text className={s.muted}>{first?.total === 1 ? "1 result" : `${first?.total ?? 0} results`}</Text>
              <Dropdown
                aria-label="Sort by"
                value={sort === "modified" ? "Newest first" : "Most relevant"}
                selectedOptions={[sort]}
                onOptionSelect={(_, d) => setParam("sort", d.optionValue === "modified" ? "modified" : undefined)}
                style={{ minWidth: 160 }}
              >
                <Option value="relevance">Most relevant</Option>
                <Option value="modified">Newest first</Option>
              </Dropdown>
            </div>
            {hits.length === 0 ? (
              <Empty title="No results">
                <Text>
                  Check the spelling or try fewer words. Use "quotes" for an exact phrase, -word to exclude a word and
                  filetype:pdf or author:name to narrow the search.
                </Text>
              </Empty>
            ) : (
              hits.map((hit) => <Result key={hit.id} hit={hit} />)
            )}
            {results.hasNextPage && (
              <Button className={s.more} disabled={results.isFetchingNextPage} onClick={() => results.fetchNextPage()}>
                {results.isFetchingNextPage ? "Loading…" : "Show more"}
              </Button>
            )}
          </div>

          <aside className={s.facets} aria-label="Filters">
            {FACETS.map((facet) => {
              const values = first?.facets[facet.key] ?? [];
              const selected = params.get(facet.key);
              if (!values.length && !selected) return null;
              return (
                <div key={facet.key} className={s.facet}>
                  <Text weight="semibold" size={300}>{facet.title}</Text>
                  {values.map((v) => {
                    const on = selected === v.value;
                    return (
                      <button
                        key={v.value}
                        type="button"
                        aria-pressed={on}
                        className={mergeClasses(s.value, on && s.selected)}
                        onClick={() => setParam(facet.key, on ? undefined : v.value)}
                      >
                        <span>{facet.label(v)}</span>
                        {on ? <DismissRegular aria-hidden /> : <span className={s.muted}>{v.count}</span>}
                      </button>
                    );
                  })}
                </div>
              );
            })}
          </aside>
        </div>
      )}
    </>
  );
}

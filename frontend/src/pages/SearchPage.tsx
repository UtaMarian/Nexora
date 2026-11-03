import { Link, useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Card, Text, makeStyles, tokens } from "@fluentui/react-components";
import { api, routeFor } from "../api/client";
import type { NodeDto } from "../api/types";
import { FileIcon } from "../components/FileIcon";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatRelative } from "../components/format";

const useStyles = makeStyles({
  list: { display: "flex", flexDirection: "column", gap: "8px" },
  item: { display: "flex", gap: "12px", alignItems: "flex-start", textDecoration: "none", color: "inherit" },
  path: { color: tokens.colorNeutralForeground3, wordBreak: "break-all" },
});

export function SearchPage() {
  const s = useStyles();
  const [params] = useSearchParams();
  const q = params.get("q") ?? "";
  const results = useQuery({
    queryKey: ["search", q],
    queryFn: () => api<NodeDto[]>(`/api/v1/search?q=${encodeURIComponent(q)}`),
    enabled: q.length > 0,
  });

  return (
    <>
      <Text as="h1" size={700} weight="semibold" block style={{ marginBottom: 16 }}>
        Results for “{q}”
      </Text>
      <ErrorBar error={results.error} />
      {results.isPending ? (
        <Loading />
      ) : !results.data?.length ? (
        <Empty title="No results">
          <Text>Search matches file, folder and library names you have access to.</Text>
        </Empty>
      ) : (
        <div className={s.list}>
          {results.data.map((n) => (
            <Card key={n.id}>
              <Link to={routeFor(n.path)} className={s.item}>
                <FileIcon kind={n.kind} name={n.name} />
                <div>
                  <Text weight="semibold" block>
                    {n.title ?? n.name}
                  </Text>
                  <Text size={200} className={s.path} block>
                    {n.path}
                  </Text>
                  <Text size={200} block>
                    Modified {formatRelative(n.modifiedAt)} by {n.modifiedBy?.displayName}
                  </Text>
                </div>
              </Link>
            </Card>
          ))}
        </div>
      )}
    </>
  );
}

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Badge, Button, Card, Switch, Text, makeStyles, tokens } from "@fluentui/react-components";
import { api } from "../api/client";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatDate } from "../components/format";
import type { SearchStatus } from "./types";

const useStyles = makeStyles({
  toggle: { marginTop: "12px" },
  grid: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(150px, 1fr))", gap: "12px", margin: "12px 0 20px" },
  stat: { display: "flex", flexDirection: "column", gap: "2px" },
  muted: { color: tokens.colorNeutralForeground3 },
  section: { display: "block", margin: "20px 0 8px" },
  row: {
    display: "flex", alignItems: "center", gap: "12px", padding: "8px 0", flexWrap: "wrap",
    borderBottom: `1px solid ${tokens.colorNeutralStroke3}`,
  },
  name: { width: "260px", flexShrink: 0 },
  detail: { flex: 1, minWidth: "180px", overflowWrap: "anywhere", color: tokens.colorNeutralForeground3 },
  actions: { display: "flex", gap: "8px", flexWrap: "wrap", marginTop: "8px" },
});

/** Central Administration: is search healthy, how far behind is it, and which documents could not be read. */
export function SearchAdminPage() {
  const s = useStyles();
  const client = useQueryClient();
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const status = useQuery({
    queryKey: ["admin", "search"],
    queryFn: () => api<SearchStatus>("/api/v1/admin/search"),
    refetchInterval: 5000,
  });
  const d = status.data;

  const run = async (path: string) => {
    setBusy(true);
    setError(undefined);
    try {
      await api(`/api/v1/admin/search/${path}`, { method: "POST" });
      await client.invalidateQueries({ queryKey: ["admin", "search"] });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const setEnabled = async (enabled: boolean) => {
    setBusy(true);
    setError(undefined);
    try {
      await api("/api/v1/admin/search/enabled", { method: "PUT", body: { enabled } });
      await Promise.all([
        client.invalidateQueries({ queryKey: ["admin", "search"] }),
        client.invalidateQueries({ queryKey: ["me"] }),
      ]);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const stats: [string, number][] = d ? [
    ["Waiting to index", d.indexing], ["Waiting to be read", d.reading], ["Documents read", d.documents.OK],
    ["No text found", d.documents.EMPTY], ["Could not be read", d.documents.REJECTED + d.documents.FAILED],
  ] : [];

  return (
    <>
      <Text as="h1" size={700} weight="semibold">Search</Text>
      <ErrorBar error={status.error ?? error} />
      {!d ? <Loading /> : (
        <>
          <div className={s.toggle}>
            <Switch
              label={d.enabled ? "Search is on" : "Search is off"}
              checked={d.enabled}
              disabled={busy}
              onChange={(_, v) => setEnabled(v.checked)}
            />
            <Text size={200} className={s.muted} block>
              {d.enabled
                ? "Turning search off hides it for everyone and pauses indexing and OCR."
                : "Search is hidden for everyone. Changes are waiting in the queue and will be indexed when you turn it back on."}
            </Text>
          </div>
          <div className={s.grid}>
            {stats.map(([label, value]) => (
              <Card key={label} className={s.stat}>
                <Text size={600} weight="semibold">{value}</Text>
                <Text size={200} className={s.muted}>{label}</Text>
              </Card>
            ))}
          </div>

          {[d.engine, d.extractor].map((c) => (
            <div key={c.name} className={s.row}>
              <Text weight="semibold" className={s.name}>{c.name}</Text>
              <Badge appearance="tint" color={c.ok ? "success" : "danger"}>{c.ok ? "Healthy" : "Unavailable"}</Badge>
              <Text size={200} className={s.detail}>{c.detail}</Text>
            </div>
          ))}

          <Text as="h2" size={500} weight="semibold" className={s.section}>Maintenance</Text>
          <Text block>
            Rebuilding empties the index and fills it again from the database. Search shows partial results until it finishes,
            but documents are not read again. Retrying reads only the documents listed below, for example after you add OCR languages.
          </Text>
          <div className={s.actions}>
            <Button disabled={busy || !d.enabled} onClick={() => run("rebuild")}>Rebuild index</Button>
            <Button disabled={busy || !d.enabled} onClick={() => run("retry")}>Retry unread documents</Button>
          </div>

          <Text as="h2" size={500} weight="semibold" className={s.section}>Documents that could not be read</Text>
          {d.unread.length === 0 ? (
            <Empty title="Nothing to report"><Text>Every document was read, or is still waiting to be.</Text></Empty>
          ) : (
            <>
              <Text size={200} className={s.muted} block>These can still be found by name and column values.</Text>
              {d.unread.map((u) => (
                <div key={u.path} className={s.row}>
                  <Text size={200} className={s.detail} style={{ color: "inherit" }}>{u.path}</Text>
                  <Text size={200} className={s.detail}>{u.detail} · {formatDate(u.extractedAt)}</Text>
                </div>
              ))}
            </>
          )}
        </>
      )}
    </>
  );
}

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Badge, Button, Dropdown, Field, Input, Option, Switch, Text, makeStyles, tokens } from "@fluentui/react-components";
import { ArrowDownloadRegular } from "@fluentui/react-icons";
import { api } from "../api/client";
import { Empty, ErrorBar, Loading } from "../components/States";
import type { LogEntry } from "./types";

const useStyles = makeStyles({
  bar: { display: "flex", gap: "12px", flexWrap: "wrap", alignItems: "flex-end", margin: "8px 0 12px" },
  list: { fontFamily: tokens.fontFamilyMonospace, fontSize: tokens.fontSizeBase200 },
  row: { padding: "4px 0", borderBottom: `1px solid ${tokens.colorNeutralStroke3}`, overflowWrap: "anywhere" },
  head: { display: "flex", gap: "8px", alignItems: "center", flexWrap: "wrap", color: tokens.colorNeutralForeground3 },
  error: { margin: "4px 0 0", whiteSpace: "pre-wrap", color: tokens.colorPaletteRedForeground1 },
});

const COLORS: Record<string, "danger" | "warning" | "informative"> = { ERROR: "danger", WARN: "warning" };

export function LogsPage() {
  const s = useStyles();
  const [level, setLevel] = useState("INFO");
  const [q, setQ] = useState("");
  const [live, setLive] = useState(true);
  const logs = useQuery({
    queryKey: ["admin", "logs", level, q],
    queryFn: () => api<LogEntry[]>(`/api/v1/admin/logs?limit=500&level=${level}&q=${encodeURIComponent(q)}`),
    refetchInterval: live ? 5000 : false,
  });

  const download = () => {
    const text = (logs.data ?? []).map((e) => `${e.time} ${e.level} ${e.logger} ${e.message}${e.error ? "\n" + e.error : ""}`).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
    a.download = "nexora-server.log";
    a.click();
    URL.revokeObjectURL(a.href);
  };

  return (
    <>
      <Text as="h1" size={700} weight="semibold">Server logs</Text>
      <Text block style={{ marginTop: 4, color: tokens.colorNeutralForeground3 }}>
        The latest 5,000 lines since the server started. For older lines use the container logs.
      </Text>
      <div className={s.bar}>
        <Field label="Minimum level">
          <Dropdown style={{ minWidth: 120 }} value={level} selectedOptions={[level]} onOptionSelect={(_, d) => setLevel(d.optionValue ?? "INFO")}>
            {["DEBUG", "INFO", "WARN", "ERROR"].map((l) => <Option key={l}>{l}</Option>)}
          </Dropdown>
        </Field>
        <Field label="Search"><Input value={q} onChange={(_, d) => setQ(d.value)} /></Field>
        <Switch label="Live" checked={live} onChange={(_, d) => setLive(d.checked)} />
        <Button icon={<ArrowDownloadRegular />} onClick={download} disabled={!logs.data?.length}>Download</Button>
      </div>
      <ErrorBar error={logs.error} />
      {logs.isPending ? <Loading /> : !logs.data?.length ? <Empty title="Nothing matches" /> : (
        <div className={s.list}>
          {logs.data.map((e) => (
            <div key={e.seq} className={s.row}>
              <div className={s.head}>
                <span>{new Date(e.time).toLocaleTimeString()}</span>
                <Badge size="small" appearance="tint" color={COLORS[e.level] ?? "informative"}>{e.level}</Badge>
                <span>{e.logger}</span>
              </div>
              <div>{e.message}</div>
              {e.error && <pre className={s.error}>{e.error}</pre>}
            </div>
          ))}
        </div>
      )}
    </>
  );
}

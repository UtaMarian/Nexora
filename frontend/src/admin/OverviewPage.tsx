import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Badge, Card, ProgressBar, Text, makeStyles, tokens } from "@fluentui/react-components";
import { api } from "../api/client";
import { ErrorBar, Loading } from "../components/States";
import { formatDate, formatSize } from "../components/format";
import type { Health, Overview } from "./types";

const useStyles = makeStyles({
  grid: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(150px, 1fr))", gap: "12px", margin: "12px 0 20px" },
  stat: { display: "flex", flexDirection: "column", gap: "2px" },
  muted: { color: tokens.colorNeutralForeground3 },
  section: { display: "block", margin: "20px 0 8px" },
  row: {
    display: "flex", alignItems: "center", gap: "12px", padding: "8px 0", flexWrap: "wrap",
    borderBottom: `1px solid ${tokens.colorNeutralStroke3}`,
  },
  name: { width: "200px", flexShrink: 0 },
  detail: { flex: 1, minWidth: "180px", overflowWrap: "anywhere", color: tokens.colorNeutralForeground3 },
  server: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(240px, 1fr))", gap: "8px 20px" },
});

const COLORS = { OK: "success", WARNING: "warning", ERROR: "danger", DISABLED: "informative" } as const;
const LABELS = { OK: "Healthy", WARNING: "Warning", ERROR: "Error", DISABLED: "Off" } as const;

function uptime(seconds: number) {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return d ? `${d} d ${h} h` : h ? `${h} h ${m} min` : `${m} min`;
}

export function OverviewPage() {
  const s = useStyles();
  const o = useQuery({ queryKey: ["admin", "overview"], queryFn: () => api<Overview>("/api/v1/admin/overview"), refetchInterval: 15000 });
  const d = o.data;
  const stats: [string, string | number][] = d ? [
    ["Sites", d.counts.site], ["Document libraries", d.counts.library], ["Lists", d.counts.list], ["Files", d.counts.file],
    ["File versions", d.counts.versions], ["Pages and news", d.counts.pages], ["Users", d.counts.users], ["Groups", d.counts.groups],
    ["Current files", formatSize(d.storedBytes)], ["All versions", formatSize(d.versionBytes)], ["In recycle bins", d.counts.recycleBin],
  ] : [];
  return (
    <>
      <Text as="h1" size={700} weight="semibold">Farm overview</Text>
      <ErrorBar error={o.error} />
      {!d ? <Loading /> : (
        <>
          <div className={s.grid}>
            {stats.map(([label, value]) => (
              <Card key={label} className={s.stat}>
                <Text size={600} weight="semibold">{value}</Text>
                <Text size={200} className={s.muted}>{label}</Text>
              </Card>
            ))}
          </div>

          <Text as="h2" size={500} weight="semibold" className={s.section}>Health</Text>
          {d.health.map((h: Health) => (
            <div key={h.component} className={s.row}>
              <Text weight="semibold" className={s.name}>{h.component}</Text>
              <Badge appearance="tint" color={COLORS[h.status]}>{LABELS[h.status]}</Badge>
              <Text size={200} className={s.detail}>{h.detail}</Text>
            </div>
          ))}

          {d.runningJobs.length > 0 && (
            <>
              <Text as="h2" size={500} weight="semibold" className={s.section}>Running now</Text>
              {d.runningJobs.map((j) => (
                <div key={j.id} className={s.row}>
                  <Link to="/admin/jobs" className={s.name}>{j.title}</Link>
                  <ProgressBar value={j.progress / 100} style={{ flex: 1, minWidth: 120 }} />
                </div>
              ))}
            </>
          )}

          <Text as="h2" size={500} weight="semibold" className={s.section}>Server</Text>
          <div className={s.server}>
            <Text>Version: {String(d.server.version)}</Text>
            <Text>Java: {String(d.server.java)}</Text>
            <Text>System: {String(d.server.os)}</Text>
            <Text>Up for {uptime(Number(d.server.uptimeSeconds))}, since {formatDate(String(d.server.startedAt))}</Text>
            <Text>Memory: {formatSize(Number(d.server.heapUsedBytes))} of {formatSize(Number(d.server.heapMaxBytes))}</Text>
            <Text>Processors: {String(d.server.processors)}</Text>
          </div>
        </>
      )}
    </>
  );
}

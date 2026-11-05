import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Badge, Button, Card, Input, Switch, Text, makeStyles, tokens } from "@fluentui/react-components";
import { api } from "../api/client";
import { useMe } from "../components/me";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatDate } from "../components/format";
import type { SharingPolicy } from "./types";

const useStyles = makeStyles({
  section: { display: "block", margin: "20px 0 8px" },
  stack: { display: "flex", flexDirection: "column", gap: "8px", marginTop: "8px" },
  row: { display: "flex", alignItems: "center", gap: "12px", padding: "8px 0", flexWrap: "wrap", borderBottom: `1px solid ${tokens.colorNeutralStroke3}` },
  grow: { flex: 1, minWidth: "200px", overflowWrap: "anywhere" },
  muted: { color: tokens.colorNeutralForeground3 },
  form: { display: "flex", gap: "8px", flexWrap: "wrap", alignItems: "center" },
});

/** Central Administration: who may share with outsiders, every active link, and whether email works. */
export function SharingAdminPage() {
  const s = useStyles();
  const qc = useQueryClient();
  const me = useMe();
  const policy = useQuery({ queryKey: ["admin", "sharing"], queryFn: () => api<SharingPolicy>("/api/v1/admin/sharing"), refetchInterval: 10000 });
  const anonymous = useQuery({ queryKey: ["admin", "anonymous"], queryFn: () => api<{ enabled: boolean }>("/api/v1/admin/anonymous") });
  const [error, setError] = useState<unknown>(null);
  const [to, setTo] = useState(me.email ?? "");
  const [sent, setSent] = useState(false);
  const d = policy.data;

  const run = async (fn: () => Promise<unknown>) => {
    setError(null);
    try {
      await fn();
      await qc.invalidateQueries({ queryKey: ["admin", "sharing"] });
    } catch (e) {
      setError(e);
    }
  };

  return (
    <>
      <Text as="h1" size={700} weight="semibold">Sharing and email</Text>
      <ErrorBar error={policy.error ?? error} />
      {!d ? <Loading /> : (
        <>
          <Text as="h2" size={500} weight="semibold" className={s.section}>Links</Text>
          <Switch
            label="Allow links that work without signing in"
            checked={d.anyoneLinksAllowed}
            onChange={(_, v) => run(() => api("/api/v1/admin/sharing", { method: "PUT", body: { anyoneLinksAllowed: v.checked } }))}
          />
          <Text block size={200} className={s.muted}>
            Turning this off stops existing links of this kind at once. Links for people in the organization keep working. A link can last at most {d.maxLinkDays} days.
          </Text>

          <Text as="h2" size={500} weight="semibold" className={s.section}>Anonymous access</Text>
          <Switch
            label="Allow site owners to open sites, libraries and lists to people who are not signed in"
            checked={!!anonymous.data?.enabled}
            disabled={!anonymous.data}
            onChange={(_, v) => run(async () => {
              await api("/api/v1/admin/anonymous", { method: "PUT", body: { enabled: v.checked } });
              await qc.invalidateQueries({ queryKey: ["admin", "anonymous"] });
            })}
          />
          <Text block size={200} className={s.muted}>
            Visitors can only read. Turning this off closes every open site at once, and owners' choices come back if you turn it on again.
          </Text>

          <Text as="h2" size={500} weight="semibold" className={s.section}>Active links</Text>
          {!d.links.length ? <Empty title="No active links"><Text>Links people create appear here.</Text></Empty> : d.links.map((l) => (
            <div key={l.id} className={s.row}>
              <div className={s.grow}>
                <Text block>{l.path ?? "(deleted item)"}</Text>
                <Text size={200} className={s.muted} block>
                  By {l.createdBy.displayName}, expires {formatDate(l.expiresAt)}, opened {l.useCount} {l.useCount === 1 ? "time" : "times"}
                </Text>
              </div>
              <Badge appearance="tint" color={l.scope === "ANYONE" ? "warning" : "informative"}>{l.scope === "ANYONE" ? "Anyone" : "Organization"}</Badge>
              {l.hasPassword && <Badge appearance="tint">Password</Badge>}
              <Button size="small" onClick={() => run(() => api(`/api/v1/admin/sharing/links/${l.id}`, { method: "DELETE" }))}>Stop link</Button>
            </div>
          ))}

          <Text as="h2" size={500} weight="semibold" className={s.section}>Email</Text>
          <Card>
            {!d.mail.enabled ? (
              <Text>Email is off, so alerts and sharing notices are not sent. Set NEXORA_MAIL_ENABLED and the SMTP settings to turn it on.</Text>
            ) : (
              <div className={s.stack}>
                <div className={s.form}>
                  <Badge appearance="tint" color={d.mailProblem ? "danger" : "success"}>{d.mailProblem ? "Unavailable" : "Healthy"}</Badge>
                  <Text>{d.mail.server}, {d.mail.waiting} waiting, {d.mail.failed} failed</Text>
                </div>
                {d.mailProblem && <Text size={200} className={s.muted}>{d.mailProblem}</Text>}
                <div className={s.form}>
                  <Input type="email" value={to} placeholder="you@example.com" aria-label="Send a test message to" onChange={(_, v) => { setTo(v.value); setSent(false); }} />
                  <Button disabled={!to.trim()} onClick={() => run(async () => {
                    await api("/api/v1/admin/sharing/test-mail", { method: "POST", body: { to } });
                    setSent(true);
                  })}>Send a test message</Button>
                  {sent && <Text size={200}>Queued</Text>}
                </div>
              </div>
            )}
          </Card>
        </>
      )}
    </>
  );
}

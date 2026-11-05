import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Badge, Button, Dialog, DialogActions, DialogBody, DialogContent, DialogSurface, DialogTitle, Dropdown, Field, Input,
  MessageBar, MessageBarBody, Option, Persona, Tab, TabList, Text, Textarea, makeStyles, tokens,
} from "@fluentui/react-components";
import { CopyRegular, DeleteRegular, LinkRegular } from "@fluentui/react-icons";
import { api } from "../api/client";
import type { NodeDto, PrincipalRef, ShareLinkDto, SharesDto } from "../api/types";
import { ErrorBar, Loading } from "../components/States";
import { formatDate } from "../components/format";
import { PeoplePicker } from "./PeoplePicker";

const useStyles = makeStyles({
  body: { display: "flex", flexDirection: "column", gap: "12px", minHeight: "320px" },
  row: { display: "flex", alignItems: "center", gap: "8px", padding: "6px 0", borderBottom: `1px solid ${tokens.colorNeutralStroke3}`, flexWrap: "wrap" },
  grow: { flex: 1, minWidth: "160px" },
  chips: { display: "flex", gap: "6px", flexWrap: "wrap" },
  muted: { color: tokens.colorNeutralForeground3 },
  fields: { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(170px, 1fr))", gap: "12px" },
});

const DAYS = [1, 7, 30, 90, 365];
const SCOPES = { ORGANIZATION: "People in the organization", ANYONE: "Anyone with the link" };
const LEVELS = { READ: "Can view", CONTRIBUTE: "Can edit" };

const linkUrl = (token: string) => `${window.location.origin}/s/${token}`;

/** Share a file or folder with people, or with a link that expires. */
export function ShareDialog({ node, onClose }: { node: NodeDto; onClose: () => void }) {
  const s = useStyles();
  const qc = useQueryClient();
  const linkable = node.kind === "FILE" || node.kind === "FOLDER";
  const [tab, setTab] = useState<"people" | "link">("people");
  const shares = useQuery({ queryKey: ["shares", node.id], queryFn: () => api<SharesDto>(`/api/v1/nodes/${node.id}/shares`) });
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const [people, setPeople] = useState<PrincipalRef[]>([]);
  const [level, setLevel] = useState<keyof typeof LEVELS>("READ");
  const [message, setMessage] = useState("");

  const [scope, setScope] = useState<keyof typeof SCOPES>("ORGANIZATION");
  const [days, setDays] = useState(30);
  const [password, setPassword] = useState("");
  const [created, setCreated] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const act = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      await qc.invalidateQueries({ queryKey: ["shares", node.id] });
      await qc.invalidateQueries({ queryKey: ["children"] });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const share = () => act(async () => {
    await api(`/api/v1/nodes/${node.id}/share-people`, { method: "POST", body: { principals: people.map((p) => p.id), level, message } });
    setPeople([]);
    setMessage("");
  });

  const createLink = () => act(async () => {
    const r = await api<{ token: string }>(`/api/v1/nodes/${node.id}/share-links`, {
      method: "POST", body: { scope, days, password: password || undefined },
    });
    setCreated(linkUrl(r.token));
    setCopied(false);
    setPassword("");
  });

  const copy = async () => {
    if (!created) return;
    await navigator.clipboard.writeText(created);
    setCopied(true);
  };

  const d = shares.data;
  const days_ = DAYS.filter((n) => !d || n <= d.maxLinkDays);

  return (
    <Dialog open onOpenChange={(_, v) => !v.open && onClose()}>
      <DialogSurface style={{ maxWidth: 580 }}>
        <DialogBody>
          <DialogTitle>Share {node.title ?? node.name}</DialogTitle>
          <DialogContent className={s.body}>
            <TabList selectedValue={tab} onTabSelect={(_, v) => setTab(v.value as "people" | "link")}>
              <Tab value="people">People</Tab>
              {linkable && <Tab value="link">Link</Tab>}
            </TabList>
            <ErrorBar error={error ?? shares.error} />
            {!d ? <Loading /> : tab === "people" ? (
              <>
                <Field label="Share with">
                  <PeoplePicker onSelect={(p) => p && setPeople((list) => (list.some((x) => x.id === p.id) ? list : [...list, p]))} />
                </Field>
                {people.length > 0 && (
                  <div className={s.chips}>
                    {people.map((p) => (
                      <Button key={p.id} size="small" onClick={() => setPeople((l) => l.filter((x) => x.id !== p.id))} title="Remove">
                        {p.displayName} ×
                      </Button>
                    ))}
                  </div>
                )}
                <Field label="What they can do">
                  <Dropdown value={LEVELS[level]} selectedOptions={[level]} onOptionSelect={(_, v) => setLevel(v.optionValue as keyof typeof LEVELS)}>
                    {Object.entries(LEVELS).map(([k, label]) => <Option key={k} value={k}>{label}</Option>)}
                  </Dropdown>
                </Field>
                {d.emailEnabled && (
                  <Field label="Message (optional)">
                    <Textarea value={message} onChange={(_, v) => setMessage(v.value)} maxLength={500} />
                  </Field>
                )}
                <div><Button appearance="primary" disabled={busy || !people.length} onClick={share}>Share</Button></div>
                {d.people.length > 0 && (
                  <div>
                    <Text weight="semibold" block>Shared with</Text>
                    {d.people.map((g) => (
                      <div key={g.id} className={s.row}>
                        <div className={s.grow}><Persona name={g.principal.displayName} secondaryText={`${LEVELS[g.level as keyof typeof LEVELS] ?? g.level}, by ${g.sharedBy.displayName}`} size="small" /></div>
                        <Button appearance="subtle" size="small" icon={<DeleteRegular />} aria-label={`Stop sharing with ${g.principal.displayName}`}
                          disabled={busy} onClick={() => act(() => api(`/api/v1/share-grants/${g.id}`, { method: "DELETE" }))} />
                      </div>
                    ))}
                  </div>
                )}
              </>
            ) : (
              <>
                {created && (
                  <MessageBar intent="success">
                    <MessageBarBody>
                      <Text block weight="semibold">Copy this link now. It is shown only once.</Text>
                      <div style={{ display: "flex", gap: 8, marginTop: 6 }}>
                        <Input readOnly value={created} style={{ flex: 1 }} aria-label="Share link" onFocus={(e) => e.target.select()} />
                        <Button icon={<CopyRegular />} onClick={copy}>{copied ? "Copied" : "Copy"}</Button>
                      </div>
                    </MessageBarBody>
                  </MessageBar>
                )}
                <div className={s.fields}>
                  <Field label="Who can open it">
                    <Dropdown value={SCOPES[scope]} selectedOptions={[scope]} onOptionSelect={(_, v) => setScope(v.optionValue as keyof typeof SCOPES)}>
                      <Option value="ORGANIZATION">{SCOPES.ORGANIZATION}</Option>
                      {d.anyoneLinksAllowed && <Option value="ANYONE">{SCOPES.ANYONE}</Option>}
                    </Dropdown>
                  </Field>
                  <Field label="Expires after">
                    <Dropdown value={`${days} ${days === 1 ? "day" : "days"}`} selectedOptions={[String(days)]} onOptionSelect={(_, v) => setDays(Number(v.optionValue))}>
                      {days_.map((n) => <Option key={n} value={String(n)}>{`${n} ${n === 1 ? "day" : "days"}`}</Option>)}
                    </Dropdown>
                  </Field>
                </div>
                <Field label="Password (optional)">
                  <Input type="password" value={password} onChange={(_, v) => setPassword(v.value)} autoComplete="new-password" />
                </Field>
                <Text size={200} className={s.muted}>Links are read-only. People see the published version, never a draft.</Text>
                <div><Button appearance="primary" icon={<LinkRegular />} disabled={busy} onClick={createLink}>Create link</Button></div>
                {d.links.length > 0 && (
                  <div>
                    <Text weight="semibold" block>Active links</Text>
                    {d.links.map((l: ShareLinkDto) => (
                      <div key={l.id} className={s.row}>
                        <div className={s.grow}>
                          <Text block>{SCOPES[l.scope]} {l.hasPassword && <Badge size="small" appearance="tint">Password</Badge>}</Text>
                          <Text size={200} className={s.muted} block>Expires {formatDate(l.expiresAt)}, opened {l.useCount} {l.useCount === 1 ? "time" : "times"}</Text>
                        </div>
                        <Button size="small" disabled={busy} onClick={() => act(() => api(`/api/v1/share-links/${l.id}`, { method: "DELETE" }))}>Stop link</Button>
                      </div>
                    ))}
                  </div>
                )}
              </>
            )}
          </DialogContent>
          <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}

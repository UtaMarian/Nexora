import { useCallback, useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { Button, Card, Field, Input, Spinner, Text, makeStyles, tokens } from "@fluentui/react-components";
import { ArrowDownloadRegular, ChevronLeftRegular, FolderRegular } from "@fluentui/react-icons";
import type { Kind } from "../api/types";
import { FileIcon } from "../components/FileIcon";
import { formatDate, formatSize } from "../components/format";
import { RETURN_KEY } from "./LoginPage";

const useStyles = makeStyles({
  page: { minHeight: "100vh", display: "flex", flexDirection: "column", alignItems: "center", gap: "20px", padding: "48px 16px", backgroundColor: tokens.colorNeutralBackground2, boxSizing: "border-box" },
  brand: { fontWeight: tokens.fontWeightBold, letterSpacing: "2px", color: tokens.colorBrandForeground1 },
  card: { width: "100%", maxWidth: "560px", padding: "24px", display: "flex", flexDirection: "column", gap: "14px" },
  head: { display: "flex", gap: "12px", alignItems: "center" },
  name: { overflowWrap: "anywhere" },
  muted: { color: tokens.colorNeutralForeground3 },
  row: { display: "flex", alignItems: "center", gap: "10px", padding: "8px 0", borderTop: `1px solid ${tokens.colorNeutralStroke3}` },
  entry: { flex: 1, minWidth: 0, overflowWrap: "anywhere", background: "none", border: "none", padding: 0, textAlign: "left", font: "inherit", color: "inherit" },
  folder: { cursor: "pointer", ":hover": { textDecoration: "underline" } },
});

interface Entry { id: string; name: string; kind: Kind; sizeBytes?: number; modifiedAt: string }
interface Opened extends Entry { sharedBy?: string; expiresAt: string; entries?: Entry[] }

type State = { status: "loading" } | { status: "open"; item: Opened } | { status: "password"; wrong: boolean } | { status: "signin" }
  | { status: "locked" } | { status: "gone" };

/** What someone sees when they open a share link. It works with or without an account. */
export function LinkPage() {
  const s = useStyles();
  const { token } = useParams();
  const [state, setState] = useState<State>({ status: "loading" });
  const [password, setPassword] = useState("");
  const [folders, setFolders] = useState<string[]>([]);
  const [error, setError] = useState("");
  const folder = folders[folders.length - 1];

  const headers = useCallback((): HeadersInit => (password ? { "X-Link-Password": password } : {}), [password]);

  const load = useCallback(async () => {
    const q = folder ? `?folder=${encodeURIComponent(folder)}` : "";
    const res = await fetch(`/api/v1/links/${encodeURIComponent(token ?? "")}${q}`, { headers: headers(), credentials: "same-origin" });
    if (res.ok) {
      setState({ status: "open", item: await res.json() });
      return;
    }
    const code = await res.json().then((b) => b.code as string).catch(() => "");
    if (code === "password_required") setState({ status: "password", wrong: false });
    else if (code === "password_wrong") setState({ status: "password", wrong: true });
    else if (code === "sign_in_required") setState({ status: "signin" });
    else if (code === "link_locked") setState({ status: "locked" });
    else setState({ status: "gone" });
  }, [token, folder, headers]);

  useEffect(() => {
    void load();
    // only a change of folder reloads by itself; a password is submitted
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [folder]);

  const download = async (entry: Entry, root: boolean) => {
    setError("");
    const q = root ? "" : `?file=${encodeURIComponent(entry.id)}`;
    const res = await fetch(`/api/v1/links/${encodeURIComponent(token ?? "")}/content${q}`, { headers: headers(), credentials: "same-origin" });
    if (!res.ok) {
      setError("This file could not be downloaded.");
      return;
    }
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement("a");
    a.href = url;
    a.download = entry.name;
    a.click();
    URL.revokeObjectURL(url);
  };

  const signIn = () => {
    try {
      sessionStorage.setItem(RETURN_KEY, window.location.pathname);
    } catch {
      /* storage unavailable */
    }
    window.location.href = "/";
  };

  let body: React.ReactNode;
  if (state.status === "loading") body = <Spinner label="Opening link…" />;
  else if (state.status === "gone") {
    body = (
      <>
        <Text as="h1" size={500} weight="semibold">This link is not available</Text>
        <Text>It may have expired or been turned off. Ask the person who sent it for a new one.</Text>
      </>
    );
  } else if (state.status === "locked") {
    body = (
      <>
        <Text as="h1" size={500} weight="semibold">Too many tries</Text>
        <Text>Wait 15 minutes, then try the password again.</Text>
      </>
    );
  } else if (state.status === "signin") {
    body = (
      <>
        <Text as="h1" size={500} weight="semibold">Sign in to open this link</Text>
        <Text>This link is for people in the organization.</Text>
        <div><Button appearance="primary" onClick={signIn}>Sign in</Button></div>
      </>
    );
  } else if (state.status === "password") {
    body = (
      <form style={{ display: "flex", flexDirection: "column", gap: 12 }} onSubmit={(e) => { e.preventDefault(); void load(); }}>
        <Text as="h1" size={500} weight="semibold">This link needs a password</Text>
        <Field label="Password" validationMessage={state.wrong ? "That password is not right" : undefined}>
          <Input type="password" value={password} onChange={(_, d) => setPassword(d.value)} autoFocus />
        </Field>
        <div><Button appearance="primary" type="submit" disabled={!password}>Open</Button></div>
      </form>
    );
  } else {
    const { item } = state;
    body = (
      <>
        <div className={s.head}>
          {folders.length > 0 && <Button appearance="subtle" icon={<ChevronLeftRegular />} aria-label="Back" onClick={() => setFolders((f) => f.slice(0, -1))} />}
          <FileIcon kind={item.kind} name={item.name} />
          <div>
            <Text as="h1" size={500} weight="semibold" block className={s.name}>{item.name}</Text>
            <Text size={200} className={s.muted} block>
              {[item.sharedBy && `Shared by ${item.sharedBy}`, `Link expires ${formatDate(item.expiresAt)}`].filter(Boolean).join(" · ")}
            </Text>
          </div>
        </div>
        {item.entries ? (
          !item.entries.length ? <Text className={s.muted}>This folder is empty.</Text> : item.entries.map((e) => (
            <div key={e.id} className={s.row}>
              {e.kind === "FOLDER" ? <FolderRegular fontSize={20} /> : <FileIcon kind={e.kind} name={e.name} />}
              {e.kind === "FOLDER" ? (
                <button type="button" className={`${s.entry} ${s.folder}`} onClick={() => setFolders((f) => [...f, e.id])}>{e.name}</button>
              ) : (
                <>
                  <span className={s.entry}>{e.name}</span>
                  <Text size={200} className={s.muted}>{formatSize(e.sizeBytes)}</Text>
                  <Button size="small" icon={<ArrowDownloadRegular />} aria-label={`Download ${e.name}`} onClick={() => download(e, false)} />
                </>
              )}
            </div>
          ))
        ) : (
          <>
            <Text className={s.muted}>{formatSize(item.sizeBytes)} · Modified {formatDate(item.modifiedAt)}</Text>
            <div><Button appearance="primary" icon={<ArrowDownloadRegular />} onClick={() => download(item, true)}>Download</Button></div>
          </>
        )}
        {error && <Text style={{ color: tokens.colorPaletteRedForeground1 }}>{error}</Text>}
      </>
    );
  }

  return (
    <div className={s.page}>
      <div className={s.brand}>NEXORA</div>
      <Card className={s.card}>{body}</Card>
    </div>
  );
}

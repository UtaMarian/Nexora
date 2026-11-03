import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Text, makeStyles, tokens } from "@fluentui/react-components";
import { ArrowLeftRegular } from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { NodeDetail } from "../api/types";
import { ErrorBar, Loading, NotFound } from "../components/States";
import type { OfficeSession } from "../components/office";

const useStyles = makeStyles({
  // fills the window below the app bar
  root: {
    position: "fixed",
    top: "48px",
    left: 0,
    right: 0,
    bottom: 0,
    display: "flex",
    flexDirection: "column",
    backgroundColor: tokens.colorNeutralBackground1,
    zIndex: 1,
  },
  bar: {
    display: "flex",
    alignItems: "center",
    gap: "8px",
    padding: "4px 8px",
    borderBottom: `1px solid ${tokens.colorNeutralStroke2}`,
    minHeight: "36px",
  },
  frame: { flex: 1, border: "none", width: "100%" },
  path: { color: tokens.colorNeutralForeground3, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" },
});

/**
 * WOPI host page: posts the access token into the editor's iframe. Everyone who opens the same file joins
 * the same live session, so co-authors see each other's cursors and edits in real time.
 */
export function OfficePage() {
  const s = useStyles();
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const formRef = useRef<HTMLFormElement>(null);
  const frameRef = useRef<HTMLIFrameElement>(null);
  const frameName = `nexora-office-${id}`;

  const detail = useQuery({ queryKey: ["node", id], queryFn: () => api<NodeDetail>(`/api/v1/nodes/${id}`) });
  const [session, setSession] = useState<OfficeSession | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let cancelled = false;
    api<OfficeSession>(`/api/v1/nodes/${id}/office-session`, {
      method: "POST",
      body: { origin: window.location.origin },
    })
      .then((s) => !cancelled && setSession(s))
      .catch((e) => !cancelled && setError(e));
    return () => {
      cancelled = true;
    };
  }, [id]);

  // submit the token into the iframe once the form is rendered
  useEffect(() => {
    if (session) formRef.current?.submit();
  }, [session]);

  const parentPath = detail.data?.breadcrumbs.at(-2)?.path;
  const close = () => {
    qc.invalidateQueries();
    navigate(parentPath ? routeFor(parentPath) : "/");
  };

  // WOPI PostMessage: handshake, and the editor's own Close button
  useEffect(() => {
    if (!session) return;
    const editorOrigin = new URL(session.actionUrl).origin;
    const onMessage = (e: MessageEvent) => {
      if (e.origin !== editorOrigin || typeof e.data !== "string") return;
      let msg: { MessageId?: string; Values?: { Status?: string } };
      try {
        msg = JSON.parse(e.data);
      } catch {
        return;
      }
      if (msg.MessageId === "App_LoadingStatus" && msg.Values?.Status === "Frame_Ready") {
        frameRef.current?.contentWindow?.postMessage(
          JSON.stringify({ MessageId: "Host_PostmessageReady", SendTime: Date.now(), Values: {} }),
          editorOrigin,
        );
      } else if (msg.MessageId === "UI_Close") {
        close();
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session, parentPath]);

  if (detail.error) return <NotFound error={detail.error} />;

  return (
    <div className={s.root}>
      <div className={s.bar}>
        <Button appearance="subtle" icon={<ArrowLeftRegular />} onClick={close}>
          Back
        </Button>
        <Text weight="semibold">{detail.data?.node.name}</Text>
        {session && !session.canWrite && <Text size={200}>(read-only)</Text>}
        <Text size={200} className={s.path}>
          {parentPath}
        </Text>
      </div>
      {error ? (
        <div style={{ padding: 16 }}>
          <ErrorBar error={error} />
        </div>
      ) : !session ? (
        <Loading label="Opening the editor…" />
      ) : (
        <>
          <form ref={formRef} action={session.actionUrl} method="post" target={frameName} style={{ display: "none" }}>
            <input type="hidden" name="access_token" value={session.accessToken} />
            <input type="hidden" name="access_token_ttl" value={String(session.accessTokenTtl)} />
          </form>
          <iframe
            ref={frameRef}
            name={frameName}
            title={`Editing ${session.fileName}`}
            className={s.frame}
            allow="clipboard-read *; clipboard-write *; fullscreen"
            allowFullScreen
          />
        </>
      )}
    </div>
  );
}

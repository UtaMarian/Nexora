import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Card, Text, makeStyles, tokens } from "@fluentui/react-components";
import { DeleteRegular } from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { AlertDto, Kind, NodeDto, PrincipalRef } from "../api/types";
import { FileIcon } from "../components/FileIcon";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatDate, formatRelative } from "../components/format";
import { FREQUENCIES } from "../dialogs/AlertDialog";

const useStyles = makeStyles({
  title: { marginBottom: "16px" },
  list: { display: "flex", flexDirection: "column", gap: "8px" },
  row: { display: "flex", alignItems: "center", gap: "12px" },
  link: { display: "flex", alignItems: "center", gap: "12px", flex: 1, minWidth: 0, textDecoration: "none", color: "inherit" },
  body: { minWidth: 0 },
  muted: { color: tokens.colorNeutralForeground3, overflowWrap: "anywhere" },
});

function Row(props: { kind: Kind; name: string; title: string; path: string; detail: string; action?: React.ReactNode }) {
  const s = useStyles();
  return (
    <Card>
      <div className={s.row}>
        <Link to={routeFor(props.path)} className={s.link}>
          <FileIcon kind={props.kind} name={props.name} />
          <div className={s.body}>
            <Text weight="semibold" block>{props.title}</Text>
            <Text size={200} className={s.muted} block>{props.detail}</Text>
          </div>
        </Link>
        {props.action}
      </div>
    </Card>
  );
}

/** The files you opened or changed lately. */
export function RecentPage() {
  const s = useStyles();
  const recent = useQuery({ queryKey: ["recent"], queryFn: () => api<{ item: NodeDto; at: string }[]>("/api/v1/me/recent") });
  return (
    <>
      <Text as="h1" size={700} weight="semibold" block className={s.title}>Recent</Text>
      <ErrorBar error={recent.error} />
      {recent.isPending ? <Loading /> : !recent.data?.length ? (
        <Empty title="Nothing here yet"><Text>Files you open or change will appear here.</Text></Empty>
      ) : (
        <div className={s.list}>
          {recent.data.map(({ item, at }) => (
            <Row key={item.id} kind={item.kind} name={item.name} title={item.title ?? item.name} path={item.path}
              detail={`${formatRelative(at)} · ${item.path}`} />
          ))}
        </div>
      )}
    </>
  );
}

/** What other people shared with you. */
export function SharedWithMePage() {
  const s = useStyles();
  const shared = useQuery({
    queryKey: ["shared-with-me"],
    queryFn: () => api<{ item: NodeDto; sharedBy: PrincipalRef; sharedAt: string }[]>("/api/v1/me/shared-with-me"),
  });
  return (
    <>
      <Text as="h1" size={700} weight="semibold" block className={s.title}>Shared with me</Text>
      <ErrorBar error={shared.error} />
      {shared.isPending ? <Loading /> : !shared.data?.length ? (
        <Empty title="Nothing shared with you yet"><Text>When someone shares a file or folder with you, it appears here.</Text></Empty>
      ) : (
        <div className={s.list}>
          {shared.data.map(({ item, sharedBy, sharedAt }) => (
            <Row key={item.id} kind={item.kind} name={item.name} title={item.title ?? item.name} path={item.path}
              detail={`Shared by ${sharedBy.displayName}, ${formatRelative(sharedAt)}`} />
          ))}
        </div>
      )}
    </>
  );
}

/** Everything you asked to be alerted about. */
export function MyAlertsPage() {
  const s = useStyles();
  const qc = useQueryClient();
  const alerts = useQuery({ queryKey: ["my-alerts"], queryFn: () => api<AlertDto[]>("/api/v1/me/alerts") });
  const remove = async (id: string) => {
    await api(`/api/v1/me/alerts/${id}`, { method: "DELETE" });
    await qc.invalidateQueries({ queryKey: ["my-alerts"] });
  };
  return (
    <>
      <Text as="h1" size={700} weight="semibold" block className={s.title}>My alerts</Text>
      <ErrorBar error={alerts.error} />
      {alerts.isPending ? <Loading /> : !alerts.data?.length ? (
        <Empty title="No alerts"><Text>Choose Alert me on a library, list, folder or file to hear about changes.</Text></Empty>
      ) : (
        <div className={s.list}>
          {alerts.data.map((a) => (
            <Row key={a.id} kind={a.kind} name={a.title} title={a.title} path={a.path}
              detail={`${FREQUENCIES[a.frequency]} · since ${formatDate(a.createdAt)}`}
              action={<Button appearance="subtle" icon={<DeleteRegular />} aria-label={`Stop alerts for ${a.title}`} onClick={() => remove(a.id)} />} />
          ))}
        </div>
      )}
    </>
  );
}

import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Badge, Button, Card, Field, Tab, TabList, Text, Textarea, makeStyles, tokens } from "@fluentui/react-components";
import {
  CheckmarkCircleRegular, CircleRegular, DismissCircleRegular, SubtractCircleRegular,
} from "@fluentui/react-icons";
import { api, routeFor } from "../api/client";
import type { Kind, PrincipalRef } from "../api/types";
import { FileIcon } from "../components/FileIcon";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatDate, formatRelative } from "../components/format";
import { officeRoute, useOffice } from "../components/office";
import { PeoplePicker } from "../dialogs/PeoplePicker";
import { APPROVAL_STATUS, type Approval } from "./types";

const useStyles = makeStyles({
  title: { marginBottom: "12px" },
  list: { display: "flex", flexDirection: "column", gap: "8px", marginTop: "12px" },
  row: { display: "flex", alignItems: "center", gap: "12px", textDecoration: "none", color: "inherit" },
  body: { flex: 1, minWidth: 0 },
  muted: { color: tokens.colorNeutralForeground3, overflowWrap: "anywhere" },
  layout: {
    display: "grid", gridTemplateColumns: "minmax(0, 2fr) minmax(260px, 1fr)", gap: "16px", alignItems: "start",
    "@media (max-width: 860px)": { gridTemplateColumns: "minmax(0, 1fr)" },
  },
  stack: { display: "flex", flexDirection: "column", gap: "10px" },
  buttons: { display: "flex", gap: "8px", flexWrap: "wrap" },
  step: { display: "flex", gap: "10px", padding: "8px 0", borderTop: `1px solid ${tokens.colorNeutralStroke3}` },
  message: { whiteSpace: "pre-wrap", overflowWrap: "anywhere" },
  comment: { display: "block", whiteSpace: "pre-wrap", marginTop: "2px", overflowWrap: "anywhere" },
});

function Status({ a }: { a: Approval }) {
  const s = APPROVAL_STATUS[a.status];
  return <Badge appearance="tint" color={s.color}>{a.kind === "REVIEW" && a.status === "APPROVED" ? "Reviewed" : s.label}</Badge>;
}

function Row({ a }: { a: Approval }) {
  const s = useStyles();
  return (
    <Card>
      <Link to={`/approvals/${a.id}`} className={s.row}>
        <FileIcon kind={a.nodeKind as Kind} name={a.itemName} />
        <div className={s.body}>
          <Text weight="semibold" block>{a.title}</Text>
          <Text size={200} className={s.muted} block>
            {a.requestedBy}, {formatRelative(a.createdAt)}{a.dueAt ? ` · due ${formatDate(a.dueAt)}` : ""}
          </Text>
        </div>
        <Status a={a} />
      </Link>
    </Card>
  );
}

/** What is waiting for you, and what you asked for. */
export function ApprovalsPage() {
  const s = useStyles();
  const [box, setBox] = useState<"waiting" | "sent">("waiting");
  const list = useQuery({ queryKey: ["approvals", box], queryFn: () => api<Approval[]>(`/api/v1/me/approvals?box=${box}`) });
  return (
    <>
      <Text as="h1" size={700} weight="semibold" block className={s.title}>Approvals</Text>
      <TabList selectedValue={box} onTabSelect={(_, d) => setBox(d.value as "waiting" | "sent")}>
        <Tab value="waiting">Waiting for me</Tab>
        <Tab value="sent">Sent by me</Tab>
      </TabList>
      <ErrorBar error={list.error} />
      {list.isPending ? <Loading /> : !list.data?.length ? (
        <Empty title={box === "waiting" ? "Nothing is waiting for you" : "You have not asked for anything"}>
          <Text>{box === "waiting" ? "Requests for your approval or review appear here." : "Choose Request approval on a file or list item."}</Text>
        </Empty>
      ) : <div className={s.list}>{list.data.map((a) => <Row key={a.id} a={a} />)}</div>}
    </>
  );
}

const ICONS = { APPROVED: CheckmarkCircleRegular, REJECTED: DismissCircleRegular, SKIPPED: SubtractCircleRegular, PENDING: CircleRegular };
const COLORS = {
  APPROVED: tokens.colorPaletteGreenForeground1, REJECTED: tokens.colorPaletteRedForeground1,
  SKIPPED: tokens.colorNeutralForeground4, PENDING: tokens.colorNeutralForeground3,
};

/** One request: the item, who has answered, and your answer. */
export function ApprovalPage() {
  const s = useStyles();
  const { id } = useParams();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const office = useOffice();
  const q = useQuery({ queryKey: ["approval", id], queryFn: () => api<Approval>(`/api/v1/approvals/${id}`) });
  const [comment, setComment] = useState("");
  const [passing, setPassing] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const a = q.data;

  const act = async (path: string, body?: unknown) => {
    setBusy(true);
    setError(null);
    try {
      await api(`/api/v1/approvals/${id}/${path}`, { method: "POST", body });
      setComment("");
      setPassing(false);
      await qc.invalidateQueries();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  if (q.isPending) return <Loading />;
  if (!a) return <ErrorBar error={q.error} />;
  const review = a.kind === "REVIEW";
  const open = office.canOpen(a.itemName) ? officeRoute(a.nodeId) : routeFor(a.path);

  return (
    <>
      <Button appearance="subtle" onClick={() => navigate("/approvals")} style={{ marginBottom: 8 }}>All approvals</Button>
      <ErrorBar error={error} />
      <div className={s.layout}>
        <div className={s.stack}>
          <Card>
            <div className={s.row}>
              <FileIcon kind={a.nodeKind as Kind} name={a.itemName} />
              <div className={s.body}>
                <Text as="h1" size={500} weight="semibold" block>{a.title}</Text>
                <Text size={200} className={s.muted} block>
                  {a.requestedBy} asked {formatRelative(a.createdAt)}
                  {a.versionLabel ? ` · version ${a.versionLabel}` : ""}{a.dueAt ? ` · due ${formatDate(a.dueAt)}` : ""}
                </Text>
              </div>
              <Status a={a} />
            </div>
            {a.message && <Text className={s.message}>{a.message}</Text>}
            {a.outcome && <Text>{a.outcome}</Text>}
            <div><Link to={open}><Button>Open {a.itemName}</Button></Link></div>
          </Card>

          {a.canDecide && (
            <Card>
              <Text weight="semibold">Your answer</Text>
              <Field label={review ? "Your feedback" : "Comment"} hint={review ? undefined : "Needed if you reject"}>
                <Textarea value={comment} onChange={(_, d) => setComment(d.value)} maxLength={2000} />
              </Field>
              <div className={s.buttons}>
                <Button appearance="primary" disabled={busy} onClick={() => act("decide", { approve: true, comment })}>{review ? "Looks good" : "Approve"}</Button>
                <Button disabled={busy || !comment.trim()} onClick={() => act("decide", { approve: false, comment })}>{review ? "Changes needed" : "Reject"}</Button>
                <Button appearance="subtle" onClick={() => setPassing((p) => !p)}>Pass to someone else</Button>
              </div>
              {passing && (
                <Field label="Pass it to">
                  <PeoplePicker onSelect={(p: PrincipalRef | null) => p && act("reassign", { principalId: p.id })} />
                </Field>
              )}
            </Card>
          )}
          {a.canCancel && (
            <div>
              <Button disabled={busy} onClick={() => confirm("Cancel this request?") && act("cancel")}>Cancel the request</Button>
            </div>
          )}
        </div>

        <Card>
          <Text weight="semibold">{review ? "Reviewers" : "Approvers"}</Text>
          {a.steps.map((step) => {
            const Icon = ICONS[step.decision];
            return (
              <div key={step.id} className={s.step}>
                <Icon fontSize={20} color={COLORS[step.decision]} style={{ flexShrink: 0 }} />
                <div className={s.body}>
                  <Text weight="semibold" block>{step.decidedBy ?? step.name}</Text>
                  <Text size={200} className={s.muted} block>
                    {step.decision === "PENDING" ? (step.active ? "Waiting for an answer" : "Waiting for their turn")
                      : step.decision === "SKIPPED" ? "Not needed"
                        : `${step.decision === "APPROVED" ? (review ? "Looks good" : "Approved") : review ? "Changes needed" : "Rejected"}, ${formatDate(step.decidedAt)}`}
                  </Text>
                  {step.comment && <Text size={200} className={s.comment}>{step.comment}</Text>}
                </div>
              </div>
            );
          })}
        </Card>
      </div>
    </>
  );
}

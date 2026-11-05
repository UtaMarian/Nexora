import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Badge,
  Button,
  Dialog,
  DialogActions,
  DialogBody,
  DialogContent,
  DialogSurface,
  DialogTitle,
  ProgressBar,
  Table,
  TableBody,
  TableCell,
  TableHeader,
  TableHeaderCell,
  TableRow,
  Text,
  tokens,
} from "@fluentui/react-components";
import { api } from "../api/client";
import { Empty, ErrorBar, Loading } from "../components/States";
import { formatDate } from "../components/format";
import { isRunning, type Job, type JobStatus } from "./types";

const COLORS: Record<JobStatus, "informative" | "brand" | "success" | "danger" | "warning"> = {
  QUEUED: "informative", RUNNING: "brand", SUCCEEDED: "success", FAILED: "danger", CANCELLED: "warning",
};
const LABELS: Record<JobStatus, string> = {
  QUEUED: "Waiting", RUNNING: "Running", SUCCEEDED: "Finished", FAILED: "Failed", CANCELLED: "Cancelled",
};

export function JobStatusBadge({ job }: { job: Job }) {
  const failed = Number(job.result?.failed ?? 0);
  if (job.status === "SUCCEEDED" && failed > 0) return <Badge appearance="tint" color="warning">Finished, {failed} failed</Badge>;
  return <Badge appearance="tint" color={COLORS[job.status]}>{LABELS[job.status]}</Badge>;
}

export function JobsPage() {
  const jobs = useQuery({
    queryKey: ["admin", "jobs"],
    queryFn: () => api<Job[]>("/api/v1/admin/jobs?limit=100"),
    refetchInterval: (q) => (q.state.data?.some(isRunning) ? 2000 : 15000),
  });
  const [open, setOpen] = useState<string | null>(null);
  return (
    <>
      <Text as="h1" size={700} weight="semibold">Jobs</Text>
      <Text block style={{ margin: "4px 0 12px", color: tokens.colorNeutralForeground3 }}>
        Backups, restores and migrations run in the background. Two run at a time; the rest wait.
      </Text>
      <ErrorBar error={jobs.error} />
      {jobs.isPending ? <Loading /> : !jobs.data?.length ? <Empty title="No jobs yet" /> : (
        <div style={{ overflowX: "auto" }}>
          <Table aria-label="Jobs" style={{ minWidth: 640 }}>
            <TableHeader>
              <TableRow>
                <TableHeaderCell>Job</TableHeaderCell>
                <TableHeaderCell style={{ width: 170 }}>Status</TableHeaderCell>
                <TableHeaderCell style={{ width: 170 }}>Started by</TableHeaderCell>
                <TableHeaderCell style={{ width: 170 }}>When</TableHeaderCell>
              </TableRow>
            </TableHeader>
            <TableBody>
              {jobs.data.map((j) => (
                <TableRow key={j.id}>
                  <TableCell>
                    <Button appearance="transparent" style={{ padding: 0, textAlign: "left", minWidth: 0 }} onClick={() => setOpen(j.id)}>{j.title}</Button>
                    {isRunning(j) && <ProgressBar value={j.progress / 100} />}
                  </TableCell>
                  <TableCell><JobStatusBadge job={j} /></TableCell>
                  <TableCell>{j.createdBy?.displayName}</TableCell>
                  <TableCell>{formatDate(j.createdAt)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {open && <JobDialog id={open} onClose={() => setOpen(null)} />}
    </>
  );
}

/** A job's log, refreshed while it runs. */
export function JobDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const qc = useQueryClient();
  const job = useQuery({
    queryKey: ["admin", "job", id],
    queryFn: () => api<Job>(`/api/v1/admin/jobs/${id}`),
    refetchInterval: (q) => (!q.state.data || isRunning(q.state.data) ? 1500 : false),
  });
  const [error, setError] = useState<unknown>(null);
  const j = job.data;
  return (
    <Dialog open onOpenChange={(_, d) => !d.open && onClose()}>
      <DialogSurface style={{ maxWidth: 820 }}>
        <DialogBody>
          <DialogTitle>{j?.title ?? "Job"}</DialogTitle>
          <DialogContent>
            <ErrorBar error={error ?? job.error} />
            {!j ? <Loading /> : (
              <>
                <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap", marginBottom: 8 }}>
                  <JobStatusBadge job={j} />
                  {j.status === "FAILED" && j.message && <Text style={{ color: tokens.colorPaletteRedForeground1 }}>{j.message}</Text>}
                  {j.result && <Text size={200}>{Object.entries(j.result).filter(([, v]) => typeof v !== "object").map(([k, v]) => `${k}: ${v}`).join(" · ")}</Text>}
                </div>
                {isRunning(j) && <ProgressBar value={j.progress / 100} style={{ marginBottom: 8 }} />}
                <pre tabIndex={0} style={{
                  maxHeight: 400, overflow: "auto", margin: 0, padding: 10, whiteSpace: "pre-wrap", overflowWrap: "anywhere",
                  fontFamily: tokens.fontFamilyMonospace, fontSize: tokens.fontSizeBase200,
                  backgroundColor: tokens.colorNeutralBackground3, borderRadius: tokens.borderRadiusMedium,
                }}>{j.log || "No log yet."}</pre>
              </>
            )}
          </DialogContent>
          <DialogActions>
            {j && isRunning(j) && (
              <Button onClick={async () => {
                try {
                  await api(`/api/v1/admin/jobs/${id}/cancel`, { method: "POST" });
                  await qc.invalidateQueries({ queryKey: ["admin", "job", id] });
                } catch (e) {
                  setError(e);
                }
              }}>Cancel job</Button>
            )}
            <Button onClick={onClose}>Close</Button>
          </DialogActions>
        </DialogBody>
      </DialogSurface>
    </Dialog>
  );
}
